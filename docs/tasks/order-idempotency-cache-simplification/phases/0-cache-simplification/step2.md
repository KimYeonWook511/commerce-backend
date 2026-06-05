# Step 2: sync-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 결정과 step 1 의 코드 변경 결과를 파악하라:

- `/docs/tasks/order-idempotency-cache-simplification/prd.md`
- `/docs/tasks/order-idempotency-cache-simplification/architecture.md`
- `/docs/tasks/order-idempotency-cache-simplification/adr.md`
- `/docs/tasks/order-idempotency-cache-simplification/api-spec.md`
- `/docs/tasks/order-idempotency-cache-simplification/db-schema.md`

step 1 결과 코드 (변경 반영 확인):

- `/src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java`
- `/src/main/java/com/commerce/order/application/OrderCreateService.java`
- `/src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`

수정 대상 루트 문서 (현재 상태 파악):

- `/docs/adr.md`
- `/docs/api-spec.md`
- `/docs/architecture.md`
- `/docs/logging-conventions.md`
- `/docs/testing-conventions.md`

수정 대상 기존 task adr (cross-reference 추가):

- `/docs/tasks/order-idempotency/adr.md`
- `/docs/tasks/event-outbox-trace-propagation/adr.md`
- `/docs/tasks/unique-find-first-policy/adr.md`

## 작업

본 step 은 step 1 의 코드 변경에 맞춰 루트 docs 와 기존 task adr 의 cross-reference 를 동기화한다. *문서만 만진다.* 코드 변경 없음.

### 1. `docs/adr.md` 갱신

#### task 표 (line 20 근처)

`order-idempotency` 줄을 `order-idempotency-cache-simplification` 으로 갱신:

```markdown
| order-idempotency-cache-simplification | [`docs/tasks/order-idempotency-cache-simplification/adr.md`](tasks/order-idempotency-cache-simplification/adr.md) | Redis 는 in-flight 차단 전용, 동시 요청 409 IN_PROGRESS (ADR-002 갱신) |
```

기존 `order-idempotency` 줄은 *대체됨* 표기로 한 줄 추가하거나 줄 자체를 갱신.

#### ADR-002 본문 (line 41-46 근처)

본문을 *결정 변경* 형태로 갱신한다. 기존 결정을 *대체됨* 으로 표시하고 신규 결정을 추가:

```markdown
### ADR-002: 주문 생성 멱등성 — Redis in-flight 차단 + DB unique 제약 최종 보장

> **본 결정은 `order-idempotency-cache-simplification` 으로 갱신됨.** 기존 결정 (Redis 1차 + RDB 최종 이중 보장, AFTER_COMMIT 결과 캐싱) 은 사용처 0건으로 폐기됨.

- **결정**: 주문 생성 요청은 멱등 키를 요구하며, Redis 는 in-flight 차단 전용, RDB unique 제약이 멱등성 진실의 단일 원천이다. `idempotencyKey` 는 클라이언트가 생성한 UUID 이며 HTTP Header (`Idempotency-Key`) 로 전달한다.
- **흐름**: Redis `reserve()` 성공 시 주문 생성 → finally `clear()` 로 마커 즉시 정리. Redis `reserve()` 실패 (다른 요청 처리 중) 시 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답. 클라이언트는 backoff 재시도.
- **Redis 장애 처리**: `reserve()`, `clear()` 의 `DataAccessException` 은 Infrastructure 에서 catch. `reserve()` → `false` fallback (잘못된 409 가 잠시 나갈 수 있음), `clear()` → warn 만 (마커 잔존은 60초 TTL 만료로 자가 회복).
- **PROCESSING TTL**: 60초. MySQL `innodb_lock_wait_timeout` (50초) + α.
- **이유**: 결과 캐싱 (COMPLETED / FAILED) 은 DB unique index find 대비 latency 차이 ms 미만이고, 캐시-DB 정합성 위험만 추가. 캐시 책임을 *in-flight 차단* 한 가지로 좁히면 인터페이스가 2개 메서드로 단순해진다.
- **트레이드오프**: 같은 키 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있음 (멱등성은 DB 상태 기준). Redis timeout 시 응답 latency 영향 (비동기 listener 도입은 별도 작업).
```

#### ADR-005 본문 (line 58-63 근처)

cart 사례는 그대로 유지. 주문 멱등성 사례 부분에 한 줄 추가:

```markdown
- **주문 멱등성 캐시는 본 정책 적용 대상에서 제외** (`order-idempotency-cache-simplification` 결정). `OrderCreateService` 가 `NOT_SUPPORTED` 라 `try-finally` 직접 호출이 자동으로 commit 이후 실행됨. listener 우회 불필요.
```

#### ADR-011 본문 (line 95-98 근처)

`"OrderCreate 는 DB find 사전 체크 추가로 행위 변경 없음"` 부분을 다음으로 갱신:

```markdown
... OrderCreate 는 `order-idempotency-cache-simplification` 에서 race window 응답이 500 → 409 IN_PROGRESS 로 변경됨 (Redis fallback 후 도달하는 진짜 race 는 여전히 안전망 500).
```

#### ADR-019 본문 (line 149-156 근처)

`"OrderIdempotencyCacheEvent 한 곳뿐"` 부분을 다음으로 갱신:

```markdown
... `OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification` 에서 제거됨 (listener / event 자체 삭제). 현재 Spring Event `@TransactionalEventListener` 사용처 0건. Outbox `trace_id` 컬럼 결정은 그대로 유효.
```

### 2. `docs/api-spec.md` 갱신

주문 생성 API (`POST /api/orders`) 응답 섹션에 `409 ORDER_IDEMPOTENCY_IN_PROGRESS` 추가. line 673-677 근처에 다음 한 블록 추가:

```markdown
- `409 ORDER_IDEMPOTENCY_IN_PROGRESS`: 같은 `Idempotency-Key` 로 다른 요청이 처리 중. 클라이언트는 backoff 후 재시도 권장.
```

(정확한 위치는 기존 응답 코드 나열 형식 따름)

### 3. `docs/architecture.md` 갱신

#### port 예시 (line 56-61 근처)

`IdempotencyStore` 예시를 본 태스크의 단순화된 인터페이스에 맞춰 갱신 (있다면).

#### AFTER_COMMIT 경계 (line 249-261 근처)

OrderIdempotencyCacheEvent 사례 다이어그램 제거 또는 *"제거됨"* 표기. 남는 사례 (Outbox 등) 로 일반화:

```markdown
#### `@TransactionalEventListener(AFTER_COMMIT)` 경계

> **주의**: `OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification` 에서 제거됨. 현재 프로젝트 내 `@TransactionalEventListener` 사용처 0건. 향후 도입 시 본 절을 갱신.
```

또는 Outbox 사례를 메인 예시로 교체.

#### 주문 멱등 키 (line 304 근처)

```markdown
- 토큰은 Redis 에 저장한다. 주문 멱등성은 Redis 에 in-flight 마커만 저장 (TTL 60초). 멱등성 진실은 `tbl_order.(member_id, idempotency_key)` unique 제약.
```

### 4. `docs/logging-conventions.md` 갱신

#### AFTER_COMMIT 경계 (line 205-212 근처)

OrderIdempotencyCacheEvent 예시 제거. Outbox 사례 또는 일반 가이드로 변경:

```markdown
##### `@TransactionalEventListener(AFTER_COMMIT)` 경계

현재 프로젝트 내 `@TransactionalEventListener` 사용처 0건 (`OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification` 에서 제거됨).

향후 listener 도입 시:
- 동기 listener (기본): 같은 스레드 MDC 유지. 별도 처리 불필요.
- 비동기 listener (`@Async`, multicaster TaskExecutor): 이벤트 객체에 traceId 동봉 (Outbox 패턴 참조).
```

### 5. `docs/testing-conventions.md` 갱신

#### RedisIdempotencyStoreTest 예시 (line 37 근처)

테스트 트리 예시에서 클래스명을 정확히 (`RedisOrderIdempotencyStoreTest`) 또는 다른 사례로 교체.

#### AFTER_COMMIT 검증 (line 74, 169 근처)

```markdown
├── AFTER_COMMIT 이후 Redis 에 실제로 저장되는가
```

같은 예시는 본 태스크로 listener 사례가 사라졌으므로 일반 가이드로 변경하거나 *"향후 listener 도입 시 적용"* 표기.

### 6. 기존 task adr cross-reference 추가

각 파일 상단 (제목 바로 아래) 에 한 줄 인용 추가.

#### `docs/tasks/order-idempotency/adr.md`

```markdown
> **본 결정은 `order-idempotency-cache-simplification` 으로 대체됨.** 새 결정은 [해당 task adr](../order-idempotency-cache-simplification/adr.md) 참조.
```

#### `docs/tasks/event-outbox-trace-propagation/adr.md`

```markdown
> **`OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification` 에서 제거됨.** Outbox traceId 전파 결정은 그대로 유효.
```

#### `docs/tasks/unique-find-first-policy/adr.md`

```markdown
> **OrderCreate 흐름은 `order-idempotency-cache-simplification` 으로 부분 갱신됨.** race window 응답이 500 → 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 로 변경. find-first 패턴 자체는 유지.
```

## Acceptance Criteria

```bash
# 코드 변경 없음. 빌드/테스트 트리거 없음.

# 핵심 키워드 추가 확인
grep -n "ORDER_IDEMPOTENCY_IN_PROGRESS" docs/api-spec.md
grep -n "in-flight" docs/adr.md
grep -n "in-flight 마커" docs/architecture.md

# cross-reference 한 줄 추가 확인
grep -n "order-idempotency-cache-simplification" docs/tasks/order-idempotency/adr.md
grep -n "order-idempotency-cache-simplification" docs/tasks/event-outbox-trace-propagation/adr.md
grep -n "order-idempotency-cache-simplification" docs/tasks/unique-find-first-policy/adr.md
```

각 grep 명령이 결과를 반환한다.

회고 문서 미수정 검증은 *검증 절차* 의 `git status` 확인 + 금지사항 룰로 수행 (negative assertion 은 AC 자동 판정에 적합하지 않아 제외).

## 검증 절차

1. 위 Acceptance Criteria grep 들이 모두 결과를 반환하는지 확인.
2. `git status` 로 코드 파일 (`src/main/`, `src/test/`) 변경 없음 확인.
3. `git status` 로 회고 문서 (`docs/tasks/*/retrospective.md`) 변경 없음 확인.
4. ADR-002 본문이 *기존 결정 폐기 표시 + 신규 결정 추가* 형태인지 (완전 재작성이 아닌지) 확인.
5. 다른 task 의 adr 본문 자체는 만지지 않고 상단 cross-reference 한 줄만 추가됐는지 확인.
6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 회고 문서 (`docs/tasks/*/retrospective.md`) 를 수정하지 마라. 이유: 회고는 시점 기록 (사용자 메모리 규칙).
- ADR-002 본문을 *완전히 다시 쓰지* 마라. 이유: ADR 은 결정 변경 흐름의 기록. 기존 결정을 *대체됨* 으로 명시하고 신규 결정을 추가하는 형태로 작성.
- 다른 task 의 ADR 본문을 수정하지 마라. 이유: 다른 task 의 결정 본문은 그 시점의 정당성 기록. 상단 cross-reference 한 줄로만 변경 표시.
- `docs/commit-conventions.md` line 77-82 의 예시 커밋 메시지를 수정하지 마라. 이유: 역사적 예시 가치.
- `commerce-workspace/docs/` 하위 문서 (api-contract.md 등) 를 만지지 마라. 이유: frontend 세션 책임 (사용자 메모리 규칙).
- 코드를 수정하지 마라. 이유: step 1 의 책임. step 2 는 문서만 만진다.
- 본 task 의 prd / architecture / adr / api-spec / db-schema 등 task 내부 문서를 수정하지 마라. 이유: File Drafting 단계에서 작성됨. step 2 는 *루트 + 기존 task* 만 만진다.
- 다른 task 의 회고 본문이 *현재 사례* 로 OrderIdempotencyCacheEvent 를 언급해도 그 회고는 수정하지 마라. 이유: 회고는 그 시점의 사실 기록. 본 변경 이후의 *현재 상태* 는 본 task 의 retrospective 로 기록 (step 3).
- 기존 테스트를 깨뜨리지 마라.
