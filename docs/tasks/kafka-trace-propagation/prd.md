# 태스크 PRD

## 태스크명

- `kafka-trace-propagation`

## 배경

- 이슈 #129(traceid-mdc-filter)에서 HTTP 요청 단위 traceId가 MDC에 push되지만, Kafka 경계에서 traceId가 단절된다.
- `StockRestoreKafkaEventProducer.send()`가 메시지를 발행할 때 traceId 헤더가 부착되지 않아 consumer 쪽 로그에서 동일 흐름을 추적할 수 없다.
- outbox relay 스케줄러가 발행한 메시지와 consumer 로그가 서로 다른 traceId를 가져 Kafka 경계를 넘어선 장애 추적이 불가능하다.
- Epic #133 운영용 로깅 체계의 P5 선행 작업이다.

## 목표

- Kafka producer가 메시지를 발행할 때 현재 MDC의 traceId를 헤더(`X-Trace-Id`)에 자동으로 부착한다.
- Kafka consumer가 메시지를 수신할 때 헤더에서 traceId를 추출해 MDC에 push한다.
- consumer 로그에서 producer와 동일한 traceId가 찍혀 Kafka 경계를 가로지른 흐름 추적이 가능해진다.

## 범위

### 포함 범위

- `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java` 신규
- `src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java` 신규
- `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaConfig.java` 신규
- `src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaConsumerConfig.java` 수정 — `TraceIdRecordInterceptor` 등록
- 통합 테스트: `src/test/java/com/commerce/common/log/kafka/TraceIdKafkaPropagationIntegrationTest.java`
- 루트 문서 갱신: `docs/adr.md`, `docs/logging-conventions.md` §8, `docs/architecture.md`

### 제외 범위

- `@Async` traceId 전파 — 프로덕션 코드 사용처 0건이므로 실제 도입 시점 PR에서 함께 추가
- `@TransactionalEventListener` 비동기 전환 시 MDC 전파 — PR-2(event-trace-propagation) 범위
- ApplicationEventMulticaster wrapping — PR-2(event-trace-propagation) 범위
- memberId·기타 MDC 키의 Kafka 헤더 전파 — 별도 후속 작업
- TraceIdConstants 상수 추출 — MdcKeys 통합 리팩토링 PR에서 일괄 처리
- OutboxEvent 컬럼에 traceId 추가 — HTTP 원본 traceId 연결은 별도 후속 작업

## 주요 시나리오

- outbox relay 스케줄러가 `stock-restore-events` 토픽에 메시지를 발행하면 헤더 `X-Trace-Id`에 traceId가 포함된다.
- consumer가 헤더가 있는 메시지를 수신하면 동일 traceId로 MDC를 설정하고 로그에 출력된다.
- consumer가 헤더가 없는 메시지를 수신하면 신규 UUID를 발급해 MDC에 설정한다.
- consumer 처리가 완료(성공 또는 실패)되면 MDC의 traceId가 정리되어 다음 메시지에 누출되지 않는다.
- DLT로 라우팅되는 메시지에도 traceId 헤더가 그대로 전파된다.

## 요구사항

- Kafka producer 메시지 발행 시 MDC `traceId` 값을 `X-Trace-Id` 헤더로 부착
- MDC에 `traceId`가 없거나 유효하지 않으면 신규 UUID 발급 후 부착
- 헤더 유효성 기준: `^[A-Za-z0-9_-]{1,64}$` 패턴 일치
- 기존 헤더가 있으면 덮어쓰지 않음 (멱등성)
- consumer `intercept` 콜백에서 헤더 추출 후 MDC push
- consumer `success` / `failure` 콜백 양쪽에서 반드시 `MDC.remove("traceId")` 호출
- `stockRestoreKafkaListenerContainerFactory`에 `TraceIdRecordInterceptor` 등록

## 제약사항

- `MDC.clear()` 금지 — `MDC.remove("traceId")`로 단일 키만 제거 (다른 MDC 키 유지)
- 상수(`TRACE_ID_HEADER`, `TRACE_ID_MDC_KEY`, 정규식)는 클래스 내부 `private static final`로 보유
  (TraceIdConstants 추출은 통합 리팩토링 PR에서 일괄 처리)
- `application.yml` 수정 없음 — `DefaultKafkaProducerFactoryCustomizer` Bean으로 producer factory 등록
- `StockRestoreKafkaEventProducer`, `StockRestoreKafkaEventConsumer` 시그니처 변경 없음
