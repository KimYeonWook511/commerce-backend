# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 결정 흐름과 결과를 파악하라:

- `/docs/tasks/order-idempotency-cache-simplification/prd.md`
- `/docs/tasks/order-idempotency-cache-simplification/architecture.md`
- `/docs/tasks/order-idempotency-cache-simplification/adr.md`
- `/docs/tasks/order-idempotency-cache-simplification/api-spec.md`
- `/docs/tasks/order-idempotency-cache-simplification/db-schema.md`

step 1, 2 결과 변경 사항:

- `/src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java`
- `/src/main/java/com/commerce/order/application/OrderCreateService.java`
- `/src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`
- `/docs/adr.md` (갱신 부분)

참고 (기존 결정 맥락):

- `/docs/tasks/order-idempotency/adr.md`
- `/docs/tasks/event-outbox-trace-propagation/adr.md`
- `/docs/tasks/unique-find-first-policy/adr.md`

## 작업

본 step 의 유일한 산출물은 `docs/tasks/order-idempotency-cache-simplification/retrospective.md` 다.

다음 섹션을 포함하여 회고록을 작성한다:

### 1. 배경

- Issue #171 (PROCESSING 동시 요청 안전망 500 문제) 의 본문 요약
- Issue #172 (FAILED enum placeholder, 정책 미정) 의 본문 요약
- 두 이슈를 *멱등성 상태 머신 전체 재설계* 관점에서 묶었다는 결정 맥락

### 2. 결정 과정 요약

사용자와의 Discuss 흐름을 압축 정리. 다음 결정 갈래를 순서대로:

- **sealed interface 도입 → 거부**: enum 으로도 충분. 기존 코드베이스 컨벤션 우선
- **COMPLETED 캐싱 제거**: DB unique index find 와 latency 차이 ms 미만. 정합성 위험만 추가
- **FAILED 캐싱 제거**: 재시도 시 같은 DB 검증 거치므로 같은 결과. 일시 실패는 retry 로 회복
- **clear 위치 검토**: catch → finally + success flag → finally + 무조건 → listener 자체 제거
- **publisher 패턴 검토**: 동기 `AFTER_COMMIT` listener 는 latency 격리 효과 0. 직접 호출이 단순
- **사전 find 위치**: reserve 뒤에 둠 (캐시의 *DB 도달 전 차단* 가치 보존)

### 3. 핵심 트레이드오프

표로 정리:

| 항목 | 채택 | 거부 | 이유 |
| --- | --- | --- | --- |
| 캐시 책임 | in-flight 차단만 | + 결과 캐싱 | 결과 캐싱 효용 작음, 정합성 위험 추가 |
| 마커 정리 방식 | Service finally 직접 호출 | publisher + AFTER_COMMIT listener | 동기 listener 는 latency 격리 0, 부가 비용만 |
| 동시 요청 응답 | 409 IN_PROGRESS | 안전망 500 (기존) | 사용자 일관성, race window 명시화 |
| Redis 마커 표현 | 단순 marker `"1"` | enum (PROCESSING/COMPLETED/FAILED) | 상태가 한 종류뿐 |
| PROCESSING TTL | 60초 | 600초 (기존) | 비정상 잔존 자가 회복 시간 단축 |
| 사전 find 위치 | reserve 뒤 | reserve 앞 | 캐시의 DB 도달 전 차단 가치 보존 |

### 4. 받아들인 한계

- 같은 idempotencyKey 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있음 (멱등성은 DB 상태 기준)
- Redis timeout 시 응답 latency 영향 (비동기 listener 도입은 별도 작업)
- Redis fallback (DataAccessException → reserve false) 시 후속 race 는 여전히 안전망 500 도달 가능 (ADR-011 정합 유지)

### 5. 변경 범위

표로 정리:

| 영역 | 변경 |
| --- | --- |
| `OrderIdempotencyStore` (port) | 4 → 2 메서드 (`reserve`, `clear`) |
| `RedisOrderIdempotencyStore` | listener `handle`, `complete`, `getCompletedOrderId`, `pushTraceIdIfMissing` 제거 |
| `OrderIdempotencyStatus` enum | 파일 삭제 |
| `OrderIdempotencyCacheEvent` | 파일 삭제 |
| `OrderCreateProcessor` | event publish 제거, ttl 인자 제거 |
| `OrderCreateService` | `attemptCreateOrder` 흡수, try-finally 단일 패턴, 409 throw 추가 |
| `OrderErrorCode` | `ORDER_IDEMPOTENCY_IN_PROGRESS` (HTTP 409) 추가 |
| `application*.yml` | PROCESSING TTL 600 → 60 |
| 테스트 | `OrderCreateServiceIdempotencyTest` 시나리오 재구성, `RedisOrderIdempotencyStoreTest` 축소, `OrderCreateConcurrencyIntegrationTest` 신규 |
| 루트 docs | ADR (4개) / api-spec / architecture / logging-conventions / testing-conventions 동기화 |
| 기존 task adr | order-idempotency / event-outbox-trace-propagation / unique-find-first-policy 상단 cross-reference 한 줄 |

### 6. 부수 효과 (이슈 close)

- **#171 PROCESSING 동시 요청 안전망 500 → 409**: 명시적 409 응답 도입으로 해결
- **#172 FAILED 캐싱 정책 결정**: *"캐싱 안 함"* 으로 결정. enum 자체 제거
- **#173 AFTER_COMMIT listener 비동기 전환**: listener 자체 제거로 자동 close

### 7. 미래 결정 시점

- **Redis timeout 잦아짐**: 비동기 listener 재도입 검토
- **외부 시스템 후처리 도입 (알림, 정산 등)**: outbox 패턴 (기존 사례)
- **도메인 이벤트 도입 (PaymentCompleted 등)**: Spring Event 부활 가능 (다중 후처리 patterns)
- **결제 PG 통합으로 트랜잭션 latency 증가**: PROCESSING TTL 재검토

### 8. 배운 점

- *캐시의 책임* 을 명확히 정의하면 인터페이스가 단순해진다. *부수 효과 (결과 캐싱)* 까지 끌어안으면 정합성 책임이 늘어난다.
- 과한 추상화 (sealed interface 등) 도입 전 *기존 단순 구조로 충분한지* 검토해야 한다.
- `@TransactionalEventListener(AFTER_COMMIT)` 은 *동기 실행* 임을 정확히 이해하지 않으면 *event 분리로 비동기 격리가 된다* 는 착각이 생긴다.
- publisher 패턴은 *진짜 비동기 분리* 또는 *다중 후처리* 가 필요할 때만 가치. *단일 책임 후처리* 에는 finally 직접 호출이 단순.
- 영향 범위 grep 을 미리 해두면 step 분해가 명확해진다 (16곳 문서 수정이 step 2 의 실제 범위).

## Acceptance Criteria

```bash
test -f docs/tasks/order-idempotency-cache-simplification/retrospective.md
```

파일이 존재한다.

```bash
grep -nE "in-flight|트레이드오프|trade-off" docs/tasks/order-idempotency-cache-simplification/retrospective.md
grep -n "#171\|#172\|#173" docs/tasks/order-idempotency-cache-simplification/retrospective.md
```

핵심 키워드와 이슈 번호가 모두 포함되어 있다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 회고록이 *결정 과정* 의 흐름을 정확히 반영하는지 확인:
   - sealed interface 거부 → enum 도 제거 흐름
   - COMPLETED → FAILED → listener 까지의 단계적 제거 흐름
   - publisher 검토 후 finally 채택 흐름
3. *받아들인 한계* 와 *미래 결정 시점* 이 포함되어 있는지 확인.
4. 코드 변경 0 건 / 루트 docs 변경 0 건 / 기존 회고 변경 0 건 확인 (`git status`).
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 회고록을 다른 step 결과와 섞지 마라. 본 step 의 *유일한* 산출물은 retrospective.md.
- 코드를 수정하지 마라. 이유: step 1 의 책임이며 이미 완료됨.
- 루트 문서를 수정하지 마라. 이유: step 2 의 책임이며 이미 완료됨.
- 기존 task 의 회고 문서를 수정하지 마라. 이유: 회고는 시점 기록이며 사후 수정 금지 (사용자 메모리 규칙).
- 본 회고록을 사후에 수정하지 마라. 이유: 회고는 시점 기록이며 immutable. 후속 변경이 필요하면 *별도 메모* 또는 *후속 task 의 retrospective* 에 기록.
- 회고록에 *향후 작업 지시* 를 넣지 마라. 이유: 회고는 *시점의 회상* 이며 *작업 계획* 이 아니다. *미래 결정 시점* 섹션은 *판단 근거* 로 표현 (예: *"X 가 발생하면 Y 를 재검토"*).
- 기존 테스트를 깨뜨리지 마라.
