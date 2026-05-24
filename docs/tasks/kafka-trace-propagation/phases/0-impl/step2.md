# Step 2: kafka-trace-integration-test

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/tasks/kafka-trace-propagation/prd.md`
- `docs/tasks/kafka-trace-propagation/architecture.md`
- `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java`
- `src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java`
- `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaConfig.java`
- `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaConsumerConfig.java`
- `src/test/java/com/commerce/support/TestcontainersSupport.java` — Kafka 컨테이너 등록 패턴
- `src/test/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaEventConsumerIntegrationTest.java` — 통합 테스트 패턴 참조

## 작업

`src/test/java/com/commerce/common/log/kafka/TraceIdKafkaPropagationIntegrationTest.java` 파일을 신규 생성하라.

### 테스트 설정

- `@Tag("docker")` 태그 부착
- `@SpringBootTest(webEnvironment = WebEnvironment.NONE)`
- `@DynamicPropertySource`로 `TestcontainersSupport.registerKafka(registry)` 호출
- 테스트 전용 토픽: `"trace-prop-it-" + UUID.randomUUID()` (테스트 간 격리)
- 테스트 전용 컨슈머 그룹: `"trace-prop-consumer-it-" + UUID.randomUUID()`
- DB, Redis, Batch AutoConfiguration 제외(`@EnableAutoConfiguration(exclude = {...})`)
- `@Import`에 포함: `TraceIdKafkaConfig.class`, `StockRestoreKafkaConsumerConfig.class`
- 테스트용 최소 consumer: `@KafkaListener`로 메시지 수신 후 `AtomicReference<String>`에 `MDC.get("traceId")` 저장, `CountDownLatch`로 수신 신호

### 시나리오 A: MDC traceId → consumer MDC 동일값

```
MDC.put("traceId", "fixed-abc-123")
kafkaTemplate.send(topic, "msg")
// consumer에서 MDC.get("traceId") == "fixed-abc-123" 확인
```

### 시나리오 B: MDC 없을 때 신규 UUID 발급

```
MDC가 없는 상태에서 kafkaTemplate.send(topic, "msg")
// consumer에서 MDC.get("traceId") != null, UUID 형식 확인
// 헤더 X-Trace-Id 값과 consumer MDC 값이 동일 확인
```

시나리오 B에서 헤더 값을 검증하려면 consumer 내부에서 `ConsumerRecord<?, ?>` 인자를 받아 `record.headers().lastHeader("X-Trace-Id")`를 읽을 수 있다.

### 시나리오 C: consume 완료 후 MDC 정리

```
kafkaTemplate.send(topic, "msg") → consumer 처리 완료 대기
// consumer 밖(별도 스레드)에서 MDC.get("traceId") == null 확인
```

RecordInterceptor.success() 호출 후 해당 consumer 스레드의 MDC가 비워지는지 검증한다. consumer 내부에서 `AtomicReference`에 저장 후 `CountDownLatch` 해제 → 테스트 스레드에서 `MDC.get("traceId") == null` 확인(consumer 스레드가 아닌 테스트 스레드의 MDC를 검증해도 무방. 핵심은 success() 이후 MDC 정리가 이루어짐을 확인).

### 시나리오 D: KafkaConsumeNonRetryableException 시 MDC 누수 없음

```
consumer에서 KafkaConsumeNonRetryableException 발생 유도
// RecordInterceptor.failure() 호출 후 MDC.get("traceId") == null 확인
```

`@MockitoBean` 또는 테스트용 consumer override로 예외를 발생시키고 MDC 정리를 검증한다.

DLT 설정이 필요하면 `outbox.stock-restore.consumer.dlt.enabled=false` property로 DLT 없이 단순 재시도 실패로 처리한다.

## Acceptance Criteria

```bash
./gradlew dockerTest --tests "com.commerce.common.log.kafka.TraceIdKafkaPropagationIntegrationTest"
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. 4개 시나리오(A, B, C, D)가 모두 통과하는지 확인한다.
3. 기존 통합 테스트 회귀 여부 확인:
   ```bash
   ./gradlew dockerTest --tests "com.commerce.outbox.stock.infrastructure.StockRestoreKafkaEventConsumerIntegrationTest"
   ```
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `Thread.sleep` 단순 대기 금지. 이유: `CountDownLatch` + `await()` 또는 Awaitility를 사용한다.
- `MDC.clear()` 직접 호출 금지. 이유: 테스트 정리는 `MDC.remove("traceId")`로만 한다.
- 기존 통합 테스트 파일 수정 금지
