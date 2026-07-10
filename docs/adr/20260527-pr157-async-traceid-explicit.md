# 비동기/이벤트 경계 traceId 전파는 명시적 동봉 방식으로 구현한다

- Status: accepted
- Date: 2026-05-27

## Context

Kafka 경계의 traceId 전파는 ProducerInterceptor + RecordInterceptor 조합으로 해결됐으나(→ PR#149), `@TransactionalEventListener(AFTER_COMMIT)`과 Outbox relay 경계에서는 여전히 traceId가 단절되어 결제 승인 → outbox 발행 → kafka consume → 재고 복구 흐름과 주문 생성 → Redis 멱등성 캐시 흐름을 단일 traceId로 추적할 수 없었다. Spring Event는 (A) 이벤트 객체에 traceId 동봉, (B) `ApplicationEventMulticaster` wrapping을 비교했다. Outbox는 (A) 스케줄러 진입 시 신규 UUID 발급, (B) DB 컬럼에 원본 traceId 저장, (C) 현행 유지(Kafka 레벨 fallback만)를 비교했다.

Spring Event는 당시 사용처가 `OrderIdempotencyCacheEvent` 한 곳뿐이라 Multicaster wrapping은 한 군데에서만 쓰일 추상화로 과했다. `OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification`(→ PR#180)에서 제거됨 (listener / event 자체 삭제). 현재 Spring Event `@TransactionalEventListener` 사용처 0건. Outbox `trace_id` 컬럼 결정은 그대로 유효. Outbox는 (A) 스케줄러 단위 발급 시 한 실행에서 여러 독립 거래가 같은 traceId를 공유해 의미가 희석되고, (C) 현행 유지 시 Kafka 레벨에서 새 UUID가 발급되어 원 HTTP 요청과 단절된다. (B) DB 컬럼 저장만이 원본 HTTP 요청의 traceId를 consumer까지 전파한다.

## Decision

Spring Event 경계는 이벤트 객체에 traceId 필드를 동봉하고, Outbox 경계는 `tbl_outbox_event.trace_id` 컬럼에 저장한 뒤 relay 시 MDC로 복원한다. 두 경계 모두 publisher 시점의 MDC traceId를 명시적으로 전달한다. Outbox 스케줄러 자체에서는 traceId를 발급하지 않고, MDC에 유효한 traceId가 없거나 outbox.trace_id가 NULL이면 MDC 조작 없이 진행한다(Kafka 인터셉터가 신규 UUID fallback).

## Consequences

Outbox 스케줄러 자체 로그는 traceId가 없다(운영 통계 로그 성격이므로 허용). 기존 outbox 데이터 및 MDC에 유효한 traceId가 없는 케이스는 outbox.trace_id를 NULL로 저장하고 relay 시 MDC 조작 없이 진행한다(Kafka 인터셉터가 신규 UUID fallback). Spring Event 객체마다 traceId 필드를 추가하는 반복 작업이 향후 필요할 수 있으며, 이벤트가 5개 이상 늘어나는 시점에 Multicaster wrapping으로 재검토한다. DB 스키마 변경(`tbl_outbox_event.trace_id VARCHAR(64) NULL`)이 필요하나 nullable이고 기존 인덱스에 영향이 없어 무중단 적용 가능하다.

상세는 `docs/tasks/event-outbox-trace-propagation/adr.md` 참조.
