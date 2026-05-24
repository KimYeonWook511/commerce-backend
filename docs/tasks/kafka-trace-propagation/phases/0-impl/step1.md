# Step 1: kafka-trace-interceptors

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/tasks/kafka-trace-propagation/prd.md`
- `docs/tasks/kafka-trace-propagation/architecture.md`
- `docs/tasks/kafka-trace-propagation/adr.md`
- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java` — MDC 키, 헤더명, 정규식 상수 패턴 참조용
- `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaConsumerConfig.java` — 수정 대상
- `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaEventProducer.java` — 검증 대상 (수정 불필요)
- `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaEventConsumer.java` — 검증 대상 (수정 불필요)

## 작업

아래 파일들을 생성 또는 수정하라.

### 신규 파일 1: `TraceIdKafkaProducerInterceptor`

`src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java`

- `implements org.apache.kafka.clients.producer.ProducerInterceptor<Object, Object>`
- `onSend`: 헤더 `X-Trace-Id`가 이미 존재하면 그대로 반환(멱등성). 없으면 `MDC.get("traceId")` 값이 유효하면 사용, 아니면 `UUID.randomUUID().toString()`으로 발급 후 헤더에 부착.
- 유효성 기준: `^[A-Za-z0-9_-]{1,64}$` 패턴 일치
- `onAcknowledgement` / `close` / `configure`: no-op
- 상수 `TRACE_ID_HEADER = "X-Trace-Id"`, `TRACE_ID_MDC_KEY = "traceId"`, 정규식은 `private static final`로 클래스 내부 보유

### 신규 파일 2: `TraceIdRecordInterceptor`

`src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java`

- `implements org.springframework.kafka.listener.RecordInterceptor<Object, Object>`
- `intercept`: 헤더 `X-Trace-Id`를 UTF-8 디코딩 후 정규식 검증 → 유효하면 해당 값, 아니면 신규 UUID → `MDC.put("traceId", traceId)` 후 record 반환
- `success` / `failure` 콜백 양쪽: `MDC.remove("traceId")` 호출
- 동일 상수를 클래스 내부 `private static final`로 보유

### 신규 파일 3: `TraceIdKafkaConfig`

`src/main/java/com/commerce/common/log/kafka/TraceIdKafkaConfig.java`

- `@Configuration`
- `@Bean TraceIdRecordInterceptor traceIdRecordInterceptor()` — Spring Bean 등록
- `@Bean DefaultKafkaProducerFactoryCustomizer traceIdKafkaProducerFactoryCustomizer()` — `producerFactory.updateConfigs(Map.of(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, TraceIdKafkaProducerInterceptor.class.getName()))` 등록

### 수정 파일: `StockRestoreKafkaConsumerConfig`

`src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaConsumerConfig.java`

- `stockRestoreKafkaListenerContainerFactory` 메서드 파라미터에 `TraceIdRecordInterceptor traceIdRecordInterceptor` 추가
- `configurer.configure(factory, consumerFactory)` 다음 줄에 `factory.setRecordInterceptor(traceIdRecordInterceptor)` 추가
- 기존 주석 삭제 금지

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. 아래를 확인한다.
   - `TraceIdKafkaProducerInterceptor`가 `ProducerInterceptor<Object, Object>` 계약을 모두 구현하는가?
   - `TraceIdRecordInterceptor.success` / `failure` 양쪽에서 `MDC.remove` 호출이 있는가?
   - `StockRestoreKafkaConsumerConfig.stockRestoreKafkaListenerContainerFactory`에 `setRecordInterceptor` 호출이 있는가?
   - 기존 테스트(`StockRestoreKafkaConsumerConfigTest`, `StockRestoreKafkaEventProducerTest`, `StockRestoreKafkaEventConsumerTest`)가 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `MDC.clear()` 사용 금지. 이유: 다른 MDC 키(memberId 등)를 함께 제거할 위험이 있다. `MDC.remove("traceId")`만 사용한다.
- `StockRestoreKafkaEventProducer` / `StockRestoreKafkaEventConsumer` 시그니처 변경 금지. 이유: interceptor가 투명하게 처리하므로 비즈니스 코드 수정 불필요.
- `TraceIdConstants` 또는 별도 상수 클래스 추가 금지. 이유: 통합 리팩토링 PR(`MdcKeys`)에서 일괄 처리한다.
- `application.yml`에 `spring.kafka.producer.properties.interceptor.classes` 추가 금지. 이유: Bean 방식으로 등록하므로 yml 수정 불필요. 중복 등록 위험이 있다.
- 기존 주석 삭제 금지
