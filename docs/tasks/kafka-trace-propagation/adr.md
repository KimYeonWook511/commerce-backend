# 태스크 ADR

## 결정 제목

- Kafka traceId 전파 메커니즘: `ProducerInterceptor` + `RecordInterceptor` 조합 채택

## 배경

Kafka producer/consumer 경계에서 traceId 연속성을 확보하는 방법으로 아래 선택지가 있었다.

- **(A) 헤더 직접 부착**: `StockRestoreKafkaEventProducer.send()`와 consumer 내부에서 직접 MDC → 헤더 전파 코드를 작성한다.
- **(B) ProducerInterceptor + RecordInterceptor**: Spring Kafka 표준 확장점을 사용해 producer/consumer 코드 외부에서 투명하게 처리한다.

## 결정 내용

**(B) ProducerInterceptor + RecordInterceptor** 조합을 채택한다.

- producer: `org.apache.kafka.clients.producer.ProducerInterceptor<Object, Object>` 구현체 `TraceIdKafkaProducerInterceptor`
- consumer: `org.springframework.kafka.listener.RecordInterceptor<Object, Object>` 구현체 `TraceIdRecordInterceptor`
- 등록: `TraceIdKafkaConfig` `@Configuration`에서 `DefaultKafkaProducerFactoryCustomizer` Bean + `TraceIdRecordInterceptor` Bean 등록

## 근거

- **producer/consumer 코드 시그니처 무손상**: `StockRestoreKafkaEventProducer`, `StockRestoreKafkaEventConsumer` 수정 없음. 향후 producer/consumer가 추가돼도 자동 적용.
- **단일 소스 보장**: `DefaultKafkaProducerFactoryCustomizer` Bean은 Spring Boot autoconfigure의 단일 ProducerFactory에만 적용된다. `application.yml` 프로퍼티 방식은 프로파일별 누락 위험이 있다.
- **RecordInterceptor afterRecord() 콜백**: error handler·DLT 발행까지 완료된 이후 호출되므로 MDC 정리 시점을 안전하게 보장한다.

## 결과

- HTTP → Kafka publish → Kafka consume 흐름에서 동일 traceId가 로그에 연결된다.
- outbox relay 스케줄러 → Kafka consumer는 스케줄러 스레드에 traceId가 없으므로 신규 UUID가 발급된다. 원 HTTP 요청 traceId 연결은 OutboxEvent 컬럼 추가가 필요한 별도 후속 작업이다.
- DLT 라우팅 메시지에도 traceId 헤더가 자동 전파된다.

### 트레이드오프

- `DefaultKafkaProducerFactoryCustomizer`는 Boot autoconfigure가 만든 단일 ProducerFactory에만 적용된다. 사용자가 직접 ProducerFactory Bean을 등록하면 customizer가 적용되지 않는다. 현재 코드베이스에 직접 등록 사례 0건.
- TraceId 관련 상수(`TRACE_ID_HEADER`, `TRACE_ID_MDC_KEY`, 정규식)가 여러 클래스에 분산된다. 이 상태는 의도적이며 후속 `MdcKeys` 통합 리팩토링 PR에서 일괄 정리한다.
