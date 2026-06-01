# 태스크 PRD

## 태스크명

- `order-idempotency-cache-simplification`

## 배경

- Issue #171 — 같은 `idempotencyKey` 로 들어온 동시 요청 중 한쪽이 `PROCESSING` 마커가 박힌 상태에서 `INSERT` race window 에 진입하면 unique 위반 후 안전망 500 응답이 발생한다. 응답 일관성이 깨지고, race catch 의 `clear()` 가 다른 요청의 마커까지 지워 연쇄 충돌의 시작점이 된다.
- Issue #172 — `OrderIdempotencyStatus.FAILED` enum 이 placeholder 로만 존재하고 어디서도 set 되지 않는다. 실패 캐싱 정책이 미정 상태로 enum 만 코드 의도를 모호하게 만들고 있다.
- 두 이슈를 같이 살피면 *멱등성 상태 머신 전체 재설계* 가 필요하다.

## 목표

- 같은 키 동시 요청에 *명시적 409 ORDER_IDEMPOTENCY_IN_PROGRESS* 응답을 반환한다. 안전망 500 위임을 race window 한 곳에서 제거한다.
- 캐시의 책임을 *in-flight 차단 전용* 으로 좁힌다. 결과 캐싱(COMPLETED) 과 실패 캐싱(FAILED) 을 제거한다.
- 멱등성 진실의 단일 원천을 DB unique 제약으로 일원화한다. Redis 는 동시 요청 차단의 *최적화 레이어* 로 의미를 명확히 한다.
- 인터페이스를 단순화하고 listener / event / status enum 등 *결과 캐싱* 책임에서 파생됐던 부가 구조를 제거한다.

## 범위

### 포함 범위

- `OrderIdempotencyStore` 인터페이스 단순화 (`reserve`, `clear` 두 메서드)
- `RedisOrderIdempotencyStore` 의 listener · `complete` · `getCompletedOrderId` 제거
- `OrderIdempotencyStatus` enum 제거
- `OrderIdempotencyCacheEvent` 클래스 제거
- `OrderCreateService.createOrder` 분기 재구성 (reserve false → 409 / reserve true → 사전 find → execute / finally clear)
- `OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS` 추가 (HTTP 409)
- PROCESSING TTL 60초로 단축
- 단위 테스트 갱신 + 동시성 통합 테스트 신규
- 루트 docs (ADR / api-spec / architecture / logging-conventions / testing-conventions) 동기화
- 기존 task adr (order-idempotency / event-outbox-trace-propagation / unique-find-first-policy) cross-reference 한 줄 추가
- 회고록 작성

### 제외 범위

- 비동기 listener 도입 (현재 의도 = 단순성 우선. Redis timeout 잦아질 경우 별도 작업)
- Redis 마커 소유권(token 기반 CAS) 도입 (finally clear 패턴으로 race 의 부작용이 사라지므로 불필요)
- Spring Event 기반 도메인 후처리 패턴 (결제 완료 알림 등) — 별도 작업
- 기존 회고 문서 수정 (회고는 시점 기록, immutable)

## 주요 시나리오

1. **정상 단일 요청**
   - `reserve` true → 사전 find empty → `processor.execute()` → 200 응답 → finally `clear()`
2. **정상 응답 후 retry (네트워크 끊김 등)**
   - 첫 요청: 위 정상 흐름 → finally `clear` 로 마커 삭제됨
   - retry: `reserve` true → 사전 find 발견 → 200 응답 → finally `clear`
3. **같은 키 동시 요청**
   - Req A: `reserve` true → processor 진행 중
   - Req B: `reserve` false → 409 `ORDER_IDEMPOTENCY_IN_PROGRESS`
   - Req A 정상 종료 후 finally `clear`
   - 클라이언트 retry (backoff): `reserve` true → 사전 find 발견 → 200
4. **PROCESSING 마커 비정상 잔존 (서버 crash 등)**
   - 60초 TTL 만료 후 같은 키 재요청: `reserve` true → 사전 find → (commit 됐다면) 발견 → 200, (commit 못 했으면) empty → execute 재시도
5. **비즈니스 실패 (ProductNotFound 등)**
   - `reserve` true → 사전 find empty → `processor.execute()` throws → finally `clear` → throw 그대로 4xx 응답
   - 같은 키 재시도: 같은 흐름. 같은 실패 또는 (상황 변경 시) 성공

## 요구사항

- 같은 `(member_id, idempotency_key)` 로 두 요청이 동시에 들어오면 정확히 한쪽만 처리가 진행되어야 한다.
- 처리 중인 키에 대한 후속 요청은 409 응답과 `ORDER_IDEMPOTENCY_IN_PROGRESS` 에러 코드를 받아야 한다.
- 정상 종료(성공/비즈니스 실패 무관) 후 마커가 즉시 정리되어 같은 키 retry 가 즉시 결과를 받을 수 있어야 한다.
- Redis 일시 장애 시에도 단독 요청은 정상 응답이 이어져야 한다. infra adapter 가 `DataAccessException` 을 `OrderIdempotencyStoreUnavailableException` 으로 변환하고 application 이 catch 해 DB unique 안전망 경로로 fallback 진행한다. 같은 키 동시 요청이 fallback 경로에 동시 진입한 race window 는 ADR-011 안전망 500 으로 흡수.
- `OrderIdempotencyStatus` enum / `OrderIdempotencyCacheEvent` 클래스 / `RedisOrderIdempotencyStore.handle` 모두 제거 후 `src/main/` 에 잔존 참조가 없어야 한다.

## 제약사항

- ADR-005 (Redis 호출은 RDB commit 이후) 원칙 유지. `OrderCreateService` 가 `@Transactional(NOT_SUPPORTED)` 이라 finally `clear()` 가 자동으로 commit 이후 호출됨.
- ADR-011 (find-first 패턴) 정합 유지. race window 의 응답만 500 → 409 IN_PROGRESS 로 변경.
- `commerce-workspace/docs/` 하위 문서는 수정하지 않는다 (frontend 세션 책임).
- 회고 문서 (`docs/tasks/*/retrospective.md`) 는 immutable. cross-reference 가 필요하면 해당 task 의 `adr.md` 상단에 한 줄만 추가.
- 동시성 통합 테스트는 `@Tag("docker")` 로 분리하여 `./gradlew dockerTest` 에서만 실행되도록 한다 (Testcontainers 기반 Redis 필요).
