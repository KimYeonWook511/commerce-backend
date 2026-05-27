# 태스크 PRD

## 태스크명

- `event-outbox-trace-propagation`

## 배경

- Epic #133 운영 로깅 체계 도입의 후속 작업으로, 이슈 #146 "비동기/Kafka/이벤트 경계 traceId 전파"를 완료한다.
- Kafka 경계는 PR #149(kafka-trace-propagation)로 이미 완료되었다.
- `@Async`는 프로덕션 코드에서 미사용이므로 본 태스크 범위에서 제외한다.
- Spring Batch는 이슈 #146에서 명시적으로 범위 밖으로 분류된다(chunk별 traceId 의미 모호).
- 본 태스크는 다음 두 경계의 traceId 전파를 다룬다:
  - `@TransactionalEventListener(AFTER_COMMIT)` 경계 — `OrderIdempotencyCacheEvent` 처리
  - Outbox relay → Kafka 경계 — `tbl_outbox_event` 저장 시점의 traceId를 relay까지 전달

## 목표

- 결제 승인 → outbox 발행 → kafka consume → 재고 복구 흐름이 같은 traceId로 묶여 추적된다.
- 주문 생성 → Redis 멱등성 캐시(AFTER_COMMIT) 처리가 같은 traceId로 묶여 추적된다.
- 운영자가 traceId 기준으로 거래 전체 흐름을 단일 검색으로 추적할 수 있다.

## 범위

### 포함 범위

- `OrderIdempotencyCacheEvent`에 traceId 동봉 (이벤트 객체 방식)
- `tbl_outbox_event`에 `trace_id` 컬럼 신규 추가
- `OutboxEvent` 도메인 / `OutboxPublishTarget` JPA Projection에 traceId 필드 추가
- `StockRestoreOutboxCreateService`에서 outbox 생성 시 MDC traceId 저장
- `StockRestoreOutboxRelayService`에서 relay 시 저장된 traceId를 MDC에 복원하고 종료 시 정리
- 통합 테스트로 traceId 전파 검증

### 제외 범위

- `@Async` traceId 전파 (프로덕션 미사용)
- Spring Batch traceId 전파 (이슈 #146에서 범위 밖)
- `ApplicationEventMulticaster` wrapping 방식 (현재 이벤트가 하나뿐이라 과한 추상화)
- Outbox 스케줄러 자체 로그의 traceId (이벤트별 traceId가 의미 있으므로 스케줄러 단위는 무의미)
- 게이트웨이 도입에 따른 incoming X-Trace-Id 신뢰 경계 재검토 (#139 별도 이슈)

## 주요 시나리오

### 시나리오 1: 주문 생성 → Redis 멱등성 캐시 (Spring Event 경계)

1. 클라이언트가 주문 생성 API 호출 (`X-Trace-Id: xyz-999`)
2. `OrderCreateProcessor.execute()`가 주문 생성 후 `OrderIdempotencyCacheEvent` 발행 (traceId=xyz-999 동봉)
3. 트랜잭션 커밋 후 `RedisOrderIdempotencyStore.handle()`가 호출됨
4. listener는 이벤트의 traceId를 MDC에 push → Redis 캐싱 로그에 traceId=xyz-999 표시
5. listener 종료 시 MDC에서 traceId 제거

### 시나리오 2: 결제 승인 → Outbox relay → Kafka consume (Outbox 경계)

1. 클라이언트가 결제 승인 API 호출 (`X-Trace-Id: abc-111`)
2. 결제 보상 흐름에서 `StockRestoreOutboxCreateService`가 outbox 생성 (DB에 trace_id=abc-111 저장)
3. 10초 후 `StockRestoreOutboxScheduler`가 PENDING 이벤트를 relay
4. `StockRestoreOutboxRelayService.publishTarget()`이 outbox의 trace_id를 MDC에 복원
5. Kafka producer가 메시지 헤더 `X-Trace-Id`에 abc-111 부착 (기존 `TraceIdKafkaProducerInterceptor`)
6. Kafka consumer가 헤더에서 traceId를 읽어 MDC에 push (기존 `TraceIdRecordInterceptor`)
7. 재고 복구 처리 로그에 traceId=abc-111 표시

## 요구사항

- `@TransactionalEventListener` 진입 시 MDC 복원, 종료 시 정리 (다른 MDC 키 영향 금지)
- Outbox 생성 시 MDC traceId가 비어있거나 유효하지 않으면 traceId 필드 null 저장 허용 (기존 데이터 호환)
- Outbox relay 시 저장된 traceId가 null이면 MDC 조작 없이 진행 (기존 ProducerInterceptor에서 신규 UUID 발급)
- Outbox relay 종료 시 finally 블록에서 MDC 정리
- 통합 테스트로 traceId 전파 검증

## 제약사항

- `MDC.clear()` 사용 금지 (다른 MDC 키 영향 위험). `LogContext.removeTraceId()`만 사용한다.
- `OutboxEvent.createPending()` 시그니처 변경 시 모든 호출처를 함께 수정한다.
- `OutboxPublishTarget` 인터페이스 변경 시 JPA Projection 쿼리도 함께 수정한다.
- ddl-auto가 prod=update 설정이므로 컬럼 추가는 자동 적용된다. 운영 배포 시 별도 SQL 검토는 운영 절차에 위임한다.
