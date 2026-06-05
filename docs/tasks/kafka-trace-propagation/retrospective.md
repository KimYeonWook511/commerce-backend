# kafka-trace-propagation 회고

## 배경과 목표

이 작업은 이슈 #129(traceid-mdc-filter) 후속으로 진행한 Kafka 경계 traceId 전파 태스크다. 로깅 Epic #133의 P5 선행 작업으로, traceid-mdc-filter(P2)에서 HTTP 요청 단위 traceId가 MDC에 주입되었으나 Kafka publish 시점에서 traceId가 헤더로 전달되지 않아 consumer 로그에서 흐름을 추적할 수 없는 상태였다.

작업 전 저장소 상태는 다음과 같았다.

- `StockRestoreKafkaEventProducer.send()`가 메시지를 발행할 때 `X-Trace-Id` 헤더 없음 → consumer 로그에 traceId 공백
- outbox relay 스케줄러가 발행한 메시지와 consumer 로그가 서로 다른 traceId를 가짐
- Kafka 경계를 넘어선 장애 추적 불가

달성하려 한 것은 producer가 현재 MDC의 traceId를 헤더에 자동 부착하고, consumer가 헤더에서 traceId를 추출해 MDC에 push하여 Kafka 경계를 가로지른 로그 흐름 연결이다.

---

## 설계 결정 요약

본 태스크 내부에서 내린 구현 차원의 결정은 `prd.md`, `architecture.md`, `adr.md`에 기록되어 있다. 회고에서는 결과만 인용한다.

| 항목 | 결정 | 근거 |
|------|------|------|
| producer 인터셉터 | `ProducerInterceptor<Object, Object>` 구현 (`TraceIdKafkaProducerInterceptor`) | 기존 producer 코드 시그니처 무손상, 향후 추가되는 producer에도 자동 적용 |
| consumer 인터셉터 | `RecordInterceptor<Object, Object>` 구현 (`TraceIdRecordInterceptor`) | Spring Kafka 표준 확장점, success/failure 콜백으로 MDC 정리 보장 |
| producer factory 등록 방식 | `DefaultKafkaProducerFactoryCustomizer` Bean | `application.yml` 프로파일별 누락 위험 없음, Boot autoconfigure 단일 ProducerFactory에 보장 |
| consumer factory 등록 방식 | `stockRestoreKafkaListenerContainerFactory`에 직접 주입 | 팩토리 단위 명시적 등록, 새 consumer factory 추가 시 동일 Bean 주입 |
| 헤더 멱등성 | 기존 헤더가 있으면 덮어쓰지 않음 | DLT 재발행 등 헤더가 이미 있는 경우 원본 traceId 보존 |
| MDC 정리 정책 | `success()` + `failure()` 양쪽에서 `MDC.remove("traceId")` | Spring Kafka 2.7+ 계약상 둘 중 하나만 호출, MDC 누수 없음 |
| 상수 위치 | 각 클래스 내부 `private static final` | 통합 리팩토링 PR(`MdcKeys`)에서 일괄 정리 예정 |

ADR-017로 별도 기록했다. traceid-mdc-filter와 달리 기존 문서에 없는 새로운 설계 결정(ProducerInterceptor vs 직접 부착, DefaultKafkaProducerFactoryCustomizer 등록 방식)이 포함되어 ADR 추가가 적절했다.

---

## 구현 범위

### 신규 생성

| 파일 | 역할 |
|------|------|
| `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java` | Kafka producer 메시지 발행 시 MDC traceId를 `X-Trace-Id` 헤더로 부착. MDC 없거나 유효하지 않으면 UUID 신규 발급 |
| `src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java` | Kafka consumer 메시지 수신 시 `X-Trace-Id` 헤더에서 traceId 추출 후 MDC push. success/failure 콜백에서 MDC 정리 |
| `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaConfig.java` | `TraceIdRecordInterceptor` Bean 등록 및 `DefaultKafkaProducerFactoryCustomizer` Bean으로 producer factory에 `TraceIdKafkaProducerInterceptor` 등록 |
| `src/test/java/com/commerce/common/log/kafka/TraceIdKafkaPropagationIntegrationTest.java` | 4개 시나리오 통합 테스트: MDC traceId → consumer 동일값(A), MDC 없을 때 신규 UUID 발급 및 헤더 일치(B), success() 후 MDC 정리(C), KafkaConsumeNonRetryableException 시 failure() 후 MDC 정리(D) |

### 수정

| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaConsumerConfig.java` | 생성자에 `TraceIdRecordInterceptor` 파라미터 추가 및 `stockRestoreKafkaListenerContainerFactory`에 `setRecordInterceptor` 호출 |
| `src/test/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaEventConsumerIntegrationTest.java` | `TraceIdKafkaConfig` `@Import` 추가 — `StockRestoreKafkaConsumerConfig`가 `TraceIdRecordInterceptor` Bean을 요구하게 되면서 누락된 import 복원 |
| `docs/adr.md` | ADR-017(Kafka traceId 전파 설계 결정) 항목 추가 |
| `docs/logging-conventions.md` | §8 비동기·이벤트 경계 절을 Kafka 구현 완료 내용으로 갱신 |
| `docs/architecture.md` | HTTP 요청 처리 Filter 절에 비동기 경계와 traceId 전파 서브섹션 신규 추가 |

---

## 한계와 후속 과제

### outbox relay 스케줄러 → consumer 원 HTTP traceId 연결 불가

outbox relay 스케줄러는 별도 스레드(`scheduler-N`)에서 실행되므로 MDC에 traceId가 없다. 현재 구현은 이 경우 신규 UUID를 발급한다. 원 HTTP 요청 traceId와 consumer 로그를 연결하려면 `OutboxEvent` 테이블에 `trace_id` 컬럼을 추가하고, relay 시 해당 값을 MDC에 push한 뒤 발행해야 한다. 이는 별도 후속 작업으로 분리되어 있다.

### `@Async`, `@TransactionalEventListener` 비동기 경계 전파 미구현

`@Async` 메서드와 `@TransactionalEventListener` 비동기 전환 시 MDC가 초기화되어 traceId가 유실된다. `@Async`는 `TaskDecorator`로, `@TransactionalEventListener`는 이벤트 publish 시점의 MDC를 복사해 전달하는 방식으로 각각 다룰 예정이며, 이는 PR-2(event-trace-propagation) 범위다. 현재 프로덕션 코드에 `@Async` 사용처가 0건이므로 이번 태스크 범위에서 제외했다.

### `TraceIdConstants` 추출 보류

`TRACE_ID_HEADER("X-Trace-Id")`, `TRACE_ID_MDC_KEY("traceId")`, 정규식(`^[A-Za-z0-9_-]{1,64}$`)이 `TraceIdKafkaProducerInterceptor`, `TraceIdRecordInterceptor`, 기존 `TraceIdFilter`, `JwtAuthenticationFilter`에 분산되어 있다. `MdcKeys` 통합 리팩토링 PR에서 일괄 추출할 예정이다.

---

## 배운 점

### `ProducerInterceptor`는 Spring IoC 외부에서 동작한다

`ProducerInterceptor`는 Kafka 네이티브 인터페이스로, Kafka 내부에서 직접 인스턴스화된다. Spring `@Autowired` 의존성 주입이 불가능하다. `DefaultKafkaProducerFactoryCustomizer`로 클래스명을 `INTERCEPTOR_CLASSES_CONFIG`에 등록하면 Kafka가 리플렉션으로 인스턴스를 생성한다. 현재 구현은 MDC 접근만 필요하므로 문제없지만, Spring Bean 주입이 필요한 경우 이 제약을 별도로 해결해야 한다.

### 신규 `@Configuration`이 기존 통합 테스트 컨텍스트에 영향을 줄 수 있다

`StockRestoreKafkaConsumerConfig`가 `TraceIdRecordInterceptor`를 생성자 파라미터로 받게 되면서, `TraceIdKafkaConfig`를 명시적으로 import하지 않은 기존 통합 테스트(`StockRestoreKafkaEventConsumerIntegrationTest`)가 Bean 주입 실패로 회귀했다. 기존 코드에 의존성을 추가할 때 기존 테스트의 Spring context 구성을 함께 점검해야 한다.

### `RecordInterceptor` success/failure 콜백의 비동기 검증에는 `await()` 필요

`success()`, `failure()` 콜백은 Kafka listener 스레드에서 호출된다. 테스트 스레드에서 `verify()`를 즉시 호출하면 콜백이 아직 실행되지 않아 검증이 실패한다. `Awaitility`의 `await().atMost()`로 비동기 완료를 기다린 뒤 검증해야 한다.

### `DLT` 재발행에 traceId가 자동 전파된다

`DeadLetterPublishingRecoverer`는 Boot autoconfigure가 만든 `KafkaTemplate`(동일 ProducerFactory 경유)을 사용한다. `TraceIdKafkaProducerInterceptor`가 ProducerFactory에 등록되어 있으므로 DLT 재발행 시에도 traceId 헤더가 자동으로 부착된다. 별도 처리가 필요하지 않다.
