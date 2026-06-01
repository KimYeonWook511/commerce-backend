# 태스크 ADR

> **`OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification` 에서 제거됨.** Outbox traceId 전파 결정은 그대로 유효.

## 결정 1: Spring Event 경계는 이벤트 객체에 traceId 동봉 방식 채택

### 배경

`@TransactionalEventListener(AFTER_COMMIT)`은 listener 실행 시점에 publisher 스레드의 MDC가 자동 전파되지 않는다. 두 가지 선택지가 있었다:

- A. 이벤트 객체에 traceId 필드 추가 (publisher에서 전달, listener에서 복원)
- B. `ApplicationEventMulticaster`를 wrapping하여 모든 이벤트에 자동 적용

### 결정 내용

**A. 이벤트 객체에 traceId 동봉 방식을 채택한다.**

### 근거

- 현재 프로젝트에 `@TransactionalEventListener` 사용처가 `OrderIdempotencyCacheEvent` 하나뿐이다.
- B 방식은 모든 이벤트에 일괄 적용되지만 구조가 복잡하고 한 군데에서만 쓰일 추상화로는 과하다.
- 향후 이벤트가 늘어나 반복 작업이 부담이 되는 시점에 B로 리팩토링한다.

### 결과

- 이벤트 클래스마다 traceId 필드를 추가하는 반복 작업이 향후 필요할 수 있음
- 구조가 단순해서 리뷰 부담이 작음
- 이벤트가 5개 이상 늘어나면 B 방식으로 통합 리팩토링 검토

### 동기 실행 시 MDC 보존 정책

`@TransactionalEventListener(AFTER_COMMIT)`은 **기본 동기 실행**이라 같은 HTTP 요청 스레드에서 listener가 호출된다. 이때 호출 스레드의 MDC에는 `TraceIdFilter`가 이미 traceId를 push해둔 상태다.

- listener는 **MDC에 유효한 traceId가 이미 있으면 그대로 보존**한다. 이벤트의 traceId로 덮어쓰지 않는다.
- MDC가 비어있을 때만 이벤트의 traceId를 push한다. push한 경우에만 `finally`에서 정리한다.
- 이 정책이 없으면 listener `finally`의 `removeTraceId()`가 호출 스레드의 원본 traceId까지 같이 제거하여, listener 이후 응답/access log에서 traceId가 유실되는 회귀가 발생한다.
- 결과적으로 이벤트의 traceId는 **향후 비동기 전환(`@Async` 또는 multicaster TaskExecutor) 시점의 fallback**으로만 활용된다.

## 결정 2: Outbox는 DB 컬럼에 traceId 저장 방식 채택

### 배경

Outbox relay는 스케줄러 기반(@Scheduled)이라 별도 스레드에서 실행된다. 이때 traceId가 어디서 와야 하는지가 핵심 결정 포인트였다. 세 가지 선택지가 있었다:

- A. 스케줄러 진입 시 신규 UUID 발급 (배치 실행 단위로 traceId 부여)
- B. `tbl_outbox_event`에 trace_id 컬럼 추가, 원래 HTTP 요청의 traceId를 그대로 사용
- C. 현행 유지 (스케줄러 로그엔 traceId 없음, Kafka producer가 신규 UUID 발급)

### 결정 내용

**B. `tbl_outbox_event`에 trace_id 컬럼 추가 방식을 채택한다.**

### 근거

- 이슈 #146의 핵심 목적은 "결제 승인 → 보상 dispatch → outbox 발행 → kafka consume → 재고 복구"가 같은 거래임을 로그로 묶는 것이다.
- A 방식은 한 번의 스케줄러 실행에서 여러 독립 주문 이벤트가 같은 traceId를 공유하게 되어 traceId 의미가 희석된다.
- C 방식은 Kafka 레벨에서 신규 UUID가 발급되어 원래 HTTP 요청과 단절된다.
- B 방식만이 결제 승인 HTTP 요청의 traceId를 Kafka consumer까지 전파한다.

### 결과

- DB 스키마 변경 필요 (`tbl_outbox_event.trace_id VARCHAR(64)`)
- `OutboxEvent` 도메인, `OutboxPublishTarget` Projection 시그니처 변경
- 기존 outbox 데이터는 trace_id가 null이지만 relay 로직이 null을 허용하므로 무중단 적용 가능
- 운영 배포 시점에 컬럼이 자동 추가됨 (ddl-auto=update)

### relay 시 MDC 보존 정책

`StockRestoreOutboxRelayService.publishTarget()`도 결정 1과 동일한 패턴을 따른다.

- 스케줄러 호출 경로에서는 MDC가 항상 비어 있으므로 outbox에 저장된 traceId가 사용된다.
- 향후 HTTP 흐름에서 relay가 직접 호출되는 경우(예: 관리자 API)에는 호출 스레드 MDC의 traceId가 보존된다.
- 결정 1의 정책과 일관성을 유지하기 위함이며, 현 시점에서는 방어적 코드 성격이다.

### ddl-auto=update 한계

`tbl_outbox_event.trace_id` 추가는 단순 NULL 컬럼 추가이므로 `ddl-auto: update`가 안전하게 처리하는 케이스다. 다만 Hibernate `ddl-auto: update`는 일반적으로 다음을 보장하지 않는다는 한계가 있다 (ADR-018과 동일):

- 컬럼 타입 변경 (예: VARCHAR 길이 확장)
- nullable / NOT NULL 제약 변경
- 컬럼 삭제

향후 `trace_id` 컬럼의 타입·제약을 변경할 일이 생기면 운영 DB는 자동 적용되지 않으므로 별도 ALTER가 필요하다. 운영 마이그레이션 일원화는 Flyway 도입 시점까지 한계로 둔다.

## 결정 3: Outbox 스케줄러 자체에서는 traceId를 발급하지 않는다

### 배경

Outbox 스케줄러는 한 번의 실행에서 여러 이벤트를 배치로 처리한다. 스케줄러 진입 시 traceId를 발급할지 고민할 수 있다.

### 결정 내용

**스케줄러 진입 시 traceId를 발급하지 않는다. 각 outbox 이벤트의 trace_id를 개별적으로 복원한다.**

### 근거

- 스케줄러 단위 traceId는 한 실행 안의 독립 이벤트들이 모두 같은 traceId를 공유하게 되어 traceId 의미가 희석된다.
- 운영 통계 로그(selected=5, published=3)는 traceId 없이도 운영 모니터링 가능하다.
- 개별 이벤트 처리 추적은 각 이벤트의 traceId로 충분하다.

### 결과

- 스케줄러 자체 시작 로그에는 traceId가 없음 (운영 통계 로그 성격이므로 허용)
- 스케줄러 안의 각 이벤트 처리 로그는 원래 HTTP 요청의 traceId로 추적 가능

## 결정 4: traceId가 없거나 유효하지 않은 케이스는 null 저장 허용

### 배경

Outbox는 HTTP 요청 외에서도 생성될 수 있다(향후 다른 스케줄러나 batch에서). 이 경우 MDC에 traceId가 없을 수 있다.

### 결정 내용

**MDC에 유효한 traceId가 없으면 outbox.trace_id를 null로 저장한다. relay 시 null이면 MDC 조작 없이 진행한다.**

### 근거

- 강제로 신규 UUID를 발급하면 "원본 흐름"이 없는 traceId가 생성되어 추적성이 떨어진다.
- relay 시 MDC가 비어있으면 기존 `TraceIdKafkaProducerInterceptor`가 신규 UUID를 발급한다. 이는 Kafka 흐름 추적의 fallback이다.
- 기존 outbox 데이터는 trace_id가 없으므로 null 호환이 필수다.

### 결과

- traceId 검증 시 `LogContext.isValidTraceId()`를 사용하여 일관성 유지
- null인 outbox 이벤트는 Kafka 레벨에서만 traceId가 생성됨 (스케줄러 자체 시작 케이스)
