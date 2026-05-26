# 태스크 아키텍처

## 개요

- 두 비동기 경계(Spring Event AFTER_COMMIT, Outbox relay)에 traceId 전파를 도입한다.
- 두 경계 모두 "이벤트 객체에 traceId 동봉" 패턴을 따른다.
  - Spring Event: 이벤트 객체에 필드 추가
  - Outbox: DB 컬럼에 trace_id 저장 후 relay 시 복원
- Kafka 경계는 이미 `TraceIdKafkaProducerInterceptor` + `TraceIdRecordInterceptor`로 처리됨. MDC의 traceId를 자동으로 헤더에 전파한다.

## 변경 대상

### Application 계층

- `com.commerce.order.application.OrderCreateProcessor` — publisher에서 traceId 동봉
- `com.commerce.order.application.event.OrderIdempotencyCacheEvent` — traceId 필드 추가
- `com.commerce.outbox.stock.application.StockRestoreOutboxCreateService` — outbox 생성 시 traceId 저장
- `com.commerce.outbox.stock.application.StockRestoreOutboxRelayService` — relay 시 MDC 복원/정리

### Domain 계층

- `com.commerce.outbox.domain.OutboxEvent` — traceId 필드 추가, `createPending()` 시그니처 확장
- `com.commerce.outbox.domain.OutboxPublishTarget` — traceId getter 추가

### Infrastructure 계층

- `com.commerce.order.infrastructure.RedisOrderIdempotencyStore` — listener에서 MDC put/remove
- `com.commerce.outbox.infrastructure.JpaOutboxEventRepository` — Projection 쿼리에 traceId select 추가

### DB

- `tbl_outbox_event`에 `trace_id VARCHAR(64)` 컬럼 신규 추가 (nullable)

## 설계 방향

### Spring Event 경계

- **이벤트 객체에 traceId 동봉**: `OrderIdempotencyCacheEvent`에 `traceId` 필드 추가
- publisher: `OrderCreateProcessor`가 `LogContext.getTraceId()`를 읽어 이벤트에 전달
- listener: `RedisOrderIdempotencyStore.handle()` 진입 시 MDC에 push, finally에서 remove
- `ApplicationEventMulticaster` wrapping은 채택하지 않음 (이벤트가 하나뿐이라 과한 추상화)

### Outbox 경계

- **DB에 trace_id 저장**: Outbox 생성 시점의 MDC traceId를 컬럼으로 저장
- relay 시점에 저장된 trace_id를 MDC에 복원 → Kafka producer가 자동으로 헤더에 전파
- 스케줄러 자체에서는 traceId를 발급하지 않음 (이벤트별 traceId가 의미 있으므로)
- traceId null 호환: 기존 데이터 또는 MDC에 traceId가 없는 케이스(스케줄러 자체 시작 등)는 null 저장 → relay 시 MDC 조작 없이 진행 → Kafka producer interceptor가 신규 UUID 발급

## 데이터 흐름

### Spring Event 경계

```
HTTP 요청 (traceId=xyz-999, MDC에 push됨)
  → OrderCreateProcessor.execute() (Transaction 진행 중)
     → publishEvent(OrderIdempotencyCacheEvent(memberId, key, orderId, ttl, traceId=xyz-999))
  → Transaction COMMIT
  → [AFTER_COMMIT 스레드]
     RedisOrderIdempotencyStore.handle(event)
       LogContext.putTraceId(event.getTraceId())  // MDC 복원
       try {
         complete(...)  // Redis 캐싱, 로그에 traceId=xyz-999
       } finally {
         LogContext.removeTraceId()
       }
```

### Outbox 경계

```
HTTP 요청 (traceId=abc-111, MDC에 push됨)
  → 결제 보상 흐름
     → StockRestoreOutboxCreateService.createOutboxEvent(command)
        OutboxEvent.createPending(..., traceId=LogContext.getTraceId())
        outboxEventRepository.save(outboxEvent)
        // DB에 trace_id=abc-111 저장

[10초 후 스케줄러]
  → StockRestoreOutboxScheduler.publishPendingEvents()
     → StockRestoreOutboxRelayService.publishPendingEvents(now)
        → publishTarget(target, now)
           LogContext.putTraceId(target.getTraceId())  // DB의 traceId를 MDC에 복원
           try {
             eventPublisher.publish(target)
             // Kafka publish → TraceIdKafkaProducerInterceptor가 MDC에서 읽어 헤더에 부착
             markSent(target)
           } finally {
             LogContext.removeTraceId()
           }
  
  → Kafka consumer
     TraceIdRecordInterceptor.intercept(record)
       헤더 X-Trace-Id=abc-111 읽어 MDC.put
     StockRestoreKafkaEventConsumer.consume(record)
       재고 복구 로그에 traceId=abc-111
```

## 예외 및 실패 처리

- **listener 내부 예외**: `RedisOrderIdempotencyStore.handle()` 내부 예외는 finally에서 MDC 정리. listener는 예외를 흡수하므로 caller에 전파되지 않음.
- **relay 발행 실패**: `publishTarget()` 내부에서 `handlePublishFailure()` 호출 시에도 MDC traceId가 유지되어 실패 로그에 traceId 포함됨. finally에서 정리.
- **traceId null인 outbox event**: `LogContext.putTraceId(null)` 호출 시 `MDC.put`이 null을 거부할 수 있음 → null 체크 후 putTraceId 호출 분기 필요.
- **MDC 누수**: 모든 진입/종료 경로에서 finally MDC 정리 (스레드 풀 재사용 시 다음 작업에 잔류 방지).

## 테스트 포인트

- `OrderCreateProcessor`가 이벤트에 현재 MDC traceId를 동봉하는가
- `RedisOrderIdempotencyStore.handle()` 진입 시 MDC에 traceId가 복원되는가
- `RedisOrderIdempotencyStore.handle()` 종료 시 MDC에서 traceId가 제거되는가
- `StockRestoreOutboxCreateService`가 현재 MDC traceId를 outbox 컬럼에 저장하는가
- MDC에 traceId가 없을 때 outbox는 trace_id=null로 저장되는가
- `StockRestoreOutboxRelayService.publishTarget()`이 outbox의 trace_id를 MDC에 복원하는가
- relay 종료 시 MDC가 정리되는가
- outbox trace_id가 null이면 MDC 조작 없이 진행하고 Kafka producer가 신규 UUID를 발급하는가 (기존 동작 유지)
- 통합 테스트: HTTP 요청 → outbox 생성 → relay → Kafka consume 흐름에서 동일 traceId 유지
