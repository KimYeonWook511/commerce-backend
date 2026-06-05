# event-outbox-trace-propagation 회고

## 배경과 목표

이 작업은 Epic #133 운영 로깅 체계 도입의 후속 태스크로, 이슈 #146 "비동기/Kafka/이벤트 경계 traceId 전파"의 잔여 범위를 완료한다. 이슈 #146 작성 시점에는 Kafka, `@Async`, `@TransactionalEventListener` 세 경계를 함께 다룰 예정이었으나, 본 태스크 진행 시점에는 코드베이스 상태가 달라져 범위를 재정의해야 했다.

작업 전 저장소 상태는 다음과 같았다.

- Kafka 경계: PR #149(kafka-trace-propagation)로 이미 완료됨. producer/consumer 인터셉터가 `X-Trace-Id` 헤더를 부착·복원하고 있었다.
- `@Async`: 프로덕션 코드에 사용처 0건이므로 본 태스크 범위에서 제외.
- Spring Batch: 이슈 #146에서 명시적으로 범위 밖(chunk별 traceId 의미가 모호).
- `@TransactionalEventListener(AFTER_COMMIT)`: `OrderIdempotencyCacheEvent` 처리에서 listener 스레드 MDC에 traceId가 없어 Redis 캐싱 로그가 원본 요청과 단절.
- Outbox relay: 별도 스케줄러 스레드에서 실행되어 MDC가 비어 있고, 결과적으로 Kafka producer 인터셉터가 신규 UUID를 발급. 결제 승인 HTTP 요청 → outbox 발행 → kafka consume → 재고 복구 흐름이 끊겼다.

달성하려 한 것은 두 경계의 traceId 전파다.

- 주문 생성 → Redis 멱등성 캐시(AFTER_COMMIT) 처리가 같은 traceId로 묶인다.
- 결제 승인 → outbox 발행 → kafka consume → 재고 복구 흐름이 같은 traceId로 묶인다.

---

## 설계 결정 요약

본 태스크에서 내린 핵심 결정 4개를 압축 요약한다. 상세 근거와 대안 비교는 `adr.md`에 기록되어 있다.

| 항목 | 결정 | 근거 |
|------|------|------|
| Spring Event 경계 | 이벤트 객체에 traceId 동봉 (`OrderIdempotencyCacheEvent`에 필드 추가) | 현재 `@TransactionalEventListener` 사용처가 한 곳뿐. `ApplicationEventMulticaster` wrapping은 한 군데에서만 쓰일 추상화로 과함 |
| Outbox 경계 | `tbl_outbox_event`에 `trace_id` 컬럼 추가, 원본 HTTP 요청 traceId를 그대로 사용 | 스케줄러 진입 시 신규 UUID 발급 방식은 한 실행의 독립 이벤트들이 traceId를 공유하게 되어 의미 희석. 현행 유지(C)는 Kafka 레벨 신규 UUID로 원본과 단절. 컬럼 저장만이 원본 traceId를 consumer까지 전파 가능 |
| 스케줄러 traceId 발급 | 발급하지 않음. 각 outbox 이벤트의 trace_id를 개별 복원 | 배치 단위 traceId는 독립 이벤트들이 공유하게 되어 traceId 의미 희석. 운영 통계 로그(selected/published)는 traceId 없이도 모니터링 가능 |
| traceId null 케이스 | null 저장 허용, relay 시 null이면 MDC 조작 없이 진행 | 강제 신규 UUID 발급은 "원본 흐름" 없는 traceId 생성으로 추적성 저하. Kafka producer 인터셉터의 fallback에 위임. 기존 outbox 데이터의 null 호환에도 필수 |

이 4개 결정을 묶어 루트 `docs/adr.md`에 ADR-019로 추가했다.

---

## 구현 범위

### Step 1: `transactional-event-trace`

**수정**

| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/commerce/order/application/event/OrderIdempotencyCacheEvent.java` | `traceId` 필드 추가 |
| `src/main/java/com/commerce/order/application/OrderCreateProcessor.java` | `LogContext.getTraceId()`를 이벤트 생성자에 전달 |
| `src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java` | `handle()` 진입 시 유효한 traceId만 MDC에 push, finally에서 `LogContext.removeTraceId()` |
| `src/test/java/com/commerce/order/application/OrderCreateProcessorTest.java` | traceId 동봉/미동봉 케이스 추가 |
| `src/test/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStoreTest.java` | 신규 — MDC push/remove 동작 검증 |

### Step 2: `outbox-trace-propagation`

**수정**

| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/commerce/outbox/domain/OutboxEvent.java` | `traceId` 필드(VARCHAR(64), nullable) 추가, `createPending()` 시그니처 확장 |
| `src/main/java/com/commerce/outbox/domain/OutboxPublishTarget.java` | `getTraceId()` 추가 |
| `src/main/java/com/commerce/outbox/infrastructure/JpaOutboxEventRepository.java` | pending/retryable Projection 쿼리에 `e.traceId as traceId` 포함 |
| `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateService.java` | `LogContext.isValidTraceId()`로 검증한 MDC traceId를 outbox에 저장 |
| `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxRelayService.java` | `publishTarget()`이 유효한 traceId만 MDC에 push, finally에서 정리 |
| `src/test/java/com/commerce/outbox/infrastructure/JpaOutboxEventRepositoryTest.java` | `createPending()` 호출에 null traceId 인자 추가 |
| `src/test/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateServiceTest.java` | traceId 저장(valid/null/invalid) 케이스 추가 |
| `src/test/java/com/commerce/outbox/stock/application/StockRestoreOutboxRelayServiceTest.java` | MDC 복원/정리/null/invalid 케이스 추가 |

### Step 3: `sync-root-docs`

**수정**

| 파일 | 변경 내용 |
|------|-----------|
| `docs/logging-conventions.md` | §8 비동기·이벤트 경계 절을 `@TransactionalEventListener`와 Outbox 경계의 구현 완료로 갱신. 적용/미적용 경계 구분과 신규 경계 가이드 추가 |
| `docs/adr.md` | ADR-019(이벤트/Outbox traceId 전파 설계 결정) 항목 추가 |
| `docs/db-schema.md` | `tbl_outbox_event`에 `trace_id VARCHAR(64) NULL` 컬럼 반영 |
| `docs/architecture.md` | 비동기 경계 절에 `TransactionalEventListener`와 Outbox 흐름 다이어그램 추가 |

### Step 4: `write-retrospective`

- `docs/tasks/event-outbox-trace-propagation/retrospective.md` 신규 생성 (본 문서)

---

## 한계와 후속 과제

### `@Async` 경계 미구현

프로덕션 코드에 `@Async` 사용처가 0건이라 본 태스크 범위에서 제외했다. 향후 `@Async` 도입 시점에 `TaskDecorator` 기반 MDC 복사 방식으로 별도 작업이 필요할 수 있다.

### Spring Batch 경계 미구현

이슈 #146에서 명시적으로 범위 밖으로 분류됐다. chunk 단위 traceId 의미를 어떻게 정의할지(chunk별 신규 발급 vs 트리거 요청 traceId 전파)가 선행 정리되어야 작업할 수 있다.

### incoming `X-Trace-Id` 신뢰 경계

현재 외부 요청의 `X-Trace-Id`를 검증 후 그대로 수용한다. 게이트웨이가 도입되면 신뢰 경계가 바뀌므로 별도 이슈 #139에서 재검토할 가능성이 있다.

### Outbox 스케줄러 운영 통계 로그

스케줄러 자체 시작 로그(`selected=N`, `published=M`)는 여전히 traceId가 없다. 운영 통계 로그 성격이므로 본 태스크에서 허용했다. 운영 모니터링에서 스케줄러 단위 추적이 필요해지면 별도 traceId 발급 방식을 재검토할 수 있다.

### `ApplicationEventMulticaster` wrapping 보류

현재 `@TransactionalEventListener` 사용처가 `OrderIdempotencyCacheEvent` 한 곳뿐이라 이벤트 객체 동봉 방식을 채택했다. 향후 이벤트 종류가 5개 이상으로 늘어나면 Multicaster wrapping 방식으로 통합 리팩토링을 검토할 가능성이 있다.

---

## 배운 점

### 이슈 작성 시점과 진행 시점의 코드 상태 차이를 먼저 확인해야 한다

이슈 #146은 Kafka, `@Async`, `@TransactionalEventListener` 세 경계를 함께 다룰 예정이었으나, 진행 시점에는 Kafka가 PR #149로 선행 완료됐고 `@Async`는 사용처가 0건이었다. 이슈 본문을 그대로 따라가지 않고 현재 코드 상태를 점검하여 작업 범위를 재정의하는 과정이 필요했다. 이슈 기반 작업 진입 시 항상 코드 상태 검증이 선행되어야 한다.

### "스케줄러 traceId 발급"은 직관적이지만 traceId 의미를 희석시키는 함정이다

Outbox relay 스케줄러는 별도 스레드에서 실행되므로 "스케줄러 진입 시 신규 UUID 발급"이 가장 단순해 보이는 방안이었다. 그러나 한 번의 스케줄러 실행이 여러 독립 outbox 이벤트(서로 다른 결제 거래)를 처리하므로, 이들이 같은 traceId를 공유하게 되어 추적성이 오히려 떨어진다. traceId는 "같은 거래/요청"을 묶기 위한 것이지 "같은 실행 스레드"를 묶기 위한 것이 아니다. 비동기 경계 traceId 설계 시 "어떤 단위를 같은 거래로 볼 것인가"를 먼저 정의해야 한다.

### Outbox 패턴에 traceId를 저장하는 결정은 ADR 등록이 필요한 결정이었다

Outbox 컬럼 추가는 단순 코드 변경처럼 보였지만 DB 스키마 변경, `OutboxEvent`/`OutboxPublishTarget` 시그니처 변경, JPA Projection 쿼리 변경, null 호환 정책 등 여러 결정이 얽혀 있었다. 또한 "스케줄러 발급 vs 컬럼 저장 vs 현행 유지"의 세 대안을 비교한 의사결정이라 향후 다른 비동기 경계(예: Batch) 작업 시 같은 의사결정을 반복할 가능성이 있어 ADR로 남기는 것이 적절했다.

### MDC null 입력 방어는 `LogContext` 헬퍼 한 곳에 모아야 일관성을 유지한다

MDC는 `null` 값을 거부할 수 있고, traceId 형식 검증(`^[A-Za-z0-9_-]{1,64}$`)도 여러 곳에서 필요했다. `LogContext.isValidTraceId()` 한 곳에서 검증을 집중시키고, listener/relay 진입부에서 일관되게 호출하는 패턴으로 정리했다. 이전 PR #156에서 `MdcKeys`로 상수를 통합한 결과 본 태스크에서 새로운 상수 분산 없이 진행할 수 있었다.

### finally 블록의 MDC 정리는 스레드 풀 재사용을 고려해 빠짐없이 적용해야 한다

`@TransactionalEventListener`와 스케줄러는 모두 스레드 풀에서 실행될 수 있다. 한 작업이 MDC를 정리하지 않으면 다음 작업에 traceId가 잔류해 잘못된 추적으로 이어진다. listener와 relay 진입부 모두 finally에서 `LogContext.removeTraceId()`를 호출하도록 일관 적용했다.
