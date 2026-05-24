# 태스크 아키텍처

## 개요

이번 태스크는 Kafka producer/consumer 경계에서 traceId를 헤더로 전파하는 횡단 관심사 작업이다. 기존 producer/consumer 코드 시그니처는 변경하지 않는다. Spring Kafka 표준 확장점(`ProducerInterceptor`, `RecordInterceptor`)을 사용해 모든 producer/consumer에 자동 적용한다.

## 변경 대상

- **새 파일 (공통 로깅 인프라)**: `src/main/java/com/commerce/common/log/kafka/`
  - `TraceIdKafkaProducerInterceptor.java` — Kafka producer 메시지에 traceId 헤더 부착
  - `TraceIdRecordInterceptor.java` — Kafka consumer 메시지에서 traceId 헤더 추출 및 MDC push/remove
  - `TraceIdKafkaConfig.java` — 두 인터셉터를 Spring Bean으로 등록
- **수정 파일**: `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaConsumerConfig.java`
  - `stockRestoreKafkaListenerContainerFactory`에 `TraceIdRecordInterceptor` 파라미터 추가 및 `setRecordInterceptor` 호출
- **변경 없는 파일**:
  - `StockRestoreKafkaEventProducer.java` — 헤더 부착이 interceptor에서 투명하게 처리됨
  - `StockRestoreKafkaEventConsumer.java` — MDC push가 interceptor에서 투명하게 처리됨

## 설계 방향

### Producer 인터셉터 등록

`DefaultKafkaProducerFactoryCustomizer` Bean을 사용해 Spring Boot autoconfigure가 생성한 단일 ProducerFactory에 `ProducerConfig.INTERCEPTOR_CLASSES_CONFIG`를 등록한다. `application.yml` 프로퍼티로 등록하지 않는 이유: 프로파일별 yml 누락 시 인터셉터가 미등록되는 위험이 있다. Bean 방식은 단일 소스로 보장된다.

### Consumer 인터셉터 등록

`RecordInterceptor<Object, Object>` 구현체를 `stockRestoreKafkaListenerContainerFactory`에 직접 주입한다. 새로운 consumer factory가 추가되면 동일 Bean을 주입하면 된다.

### 상수 보유 위치

헤더명(`X-Trace-Id`), MDC 키(`traceId`), 정규식(`^[A-Za-z0-9_-]{1,64}$`)은 각 클래스 내부에 `private static final`로 보유한다. `TraceIdFilter`, `JwtAuthenticationFilter` 등 다른 클래스에도 동일 상수가 분산되어 있으며, 통합 리팩토링 PR(`MdcKeys`)에서 일괄 정리한다.

### MDC 정리 정책

`RecordInterceptor`의 `success` / `failure` 콜백 양쪽에서 `MDC.remove("traceId")`를 호출한다. Spring Kafka 2.7+ 계약상 둘 중 정확히 하나가 호출되므로 누수 없음. `MDC.clear()`는 사용하지 않는다.

### DLT 자동 커버

`DeadLetterPublishingRecoverer`는 autoconfig가 만든 `KafkaTemplate`(동일 ProducerFactory 경유)을 사용하므로 DLT 발행 시에도 `TraceIdKafkaProducerInterceptor`가 자동 적용된다. 별도 처리 불필요.

## 데이터 흐름

```
outbox relay 스케줄러 (스레드: scheduler-N)
  MDC.get("traceId") = null  ← 스케줄러 자체 traceId 없음
  ↓
StockRestoreKafkaEventProducer.send(topic, key, message)
  ↓
KafkaTemplate.send()
  ↓
TraceIdKafkaProducerInterceptor.onSend()
  MDC "traceId" 없음 → UUID 신규 발급
  headers.add("X-Trace-Id", uuid)
  ↓
Kafka broker (stock-restore-events topic)
  ↓
TraceIdRecordInterceptor.intercept()
  headers.lastHeader("X-Trace-Id") 읽기 → 정규식 검증 → MDC.put("traceId", traceId)
  ↓
StockRestoreKafkaEventConsumer.consume(String message)
  MDC.get("traceId") = <producer가 부착한 UUID>  ← 동일 흐름
  [로그에 traceId 출력]
  ↓
TraceIdRecordInterceptor.success() 또는 failure()
  MDC.remove("traceId")
```

## 예외 및 실패 처리

- **consumer에서 예외 발생 시**: `RecordInterceptor.failure()` 콜백이 호출되어 `MDC.remove`가 보장된다. Spring Kafka `DefaultErrorHandler`가 예외를 처리(재시도 or DLT 라우팅)하며 MDC 누수 없음.
- **헤더 값 유효하지 않음**: `resolveTraceId`에서 정규식 불통과 → 신규 UUID 발급. 로그 인젝션 차단.
- **MDC 누수 방지**: `success` + `failure` 양쪽에서 `remove` 호출. Spring Kafka 계약상 하나만 호출되므로 중복 제거가 발생해도 무해하다.

## 테스트 포인트

- MDC `traceId` 있을 때 publish → consumer MDC에서 동일 값 확인
- MDC 없이 publish (outbox relay 시뮬레이션) → 신규 UUID 발급, 헤더와 consumer MDC 일치
- consume 완료 후 MDC `traceId` == null (정리 보장)
- `KafkaConsumeNonRetryableException` 발생 시도 MDC 정리 보장 (failure 콜백 호출 확인)
