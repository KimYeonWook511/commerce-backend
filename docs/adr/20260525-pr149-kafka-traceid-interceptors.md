# Kafka traceId 전파는 ProducerInterceptor + RecordInterceptor 조합으로 구현한다

- Status: accepted
- Date: 2026-05-25

## Context

HTTP 요청 단위 traceId(이슈 #129, traceid-mdc-filter)가 Kafka 경계에서 단절되어 producer-consumer 흐름 추적이 불가능했다. 해결 방법으로 (A) 헤더 직접 부착(producer/consumer 코드 수정), (B) Spring Kafka 표준 확장점(ProducerInterceptor + RecordInterceptor)을 비교했다.

(B)가 producer/consumer 코드 시그니처를 무손상으로 유지하고, 향후 추가되는 producer/consumer에도 자동 적용된다. `DefaultKafkaProducerFactoryCustomizer` Bean 등록 방식은 `application.yml` 프로퍼티 방식 대비 프로파일별 누락 위험이 없다. `RecordInterceptor.afterRecord()` 콜백은 error handler·DLT 발행까지 완료된 이후 호출되므로 MDC 정리 시점이 보장된다.

## Decision

Kafka producer가 메시지를 발행할 때 `TraceIdKafkaProducerInterceptor`가 MDC `traceId`를 헤더 `X-Trace-Id`에 부착하고, consumer가 수신할 때 `TraceIdRecordInterceptor`가 헤더에서 traceId를 추출해 MDC에 push한다.

## Consequences

outbox relay 스케줄러 → consumer 흐름에서 원 HTTP 요청 traceId와 consumer 로그가 연결되지 않는다. 이 연결은 OutboxEvent에 traceId 컬럼 추가가 필요하며 별도 후속 작업으로 분리된다. 상세는 `docs/tasks/kafka-trace-propagation/adr.md` 참조.
