# 기능 아키텍처

## 개요

주문 생성 멱등성 보장 방식을 Redis 단독에서 Redis(1차) + RDB unique 제약(최종)의 이중 구조로 전환한다.
`complete()` 호출을 AFTER_COMMIT 이벤트로 분리하여 Redis 장애가 RDB 커밋에 영향을 주지 않도록 한다.

## 변경 대상

| 레이어 | 변경 대상 |
|--------|-----------|
| Domain | `Order.java` — `idempotencyKey` 필드 추가, `create(Member, String)` 오버로드 추가 |
| Domain | `OrderRepository.java` (port) — `findByMemberIdAndIdempotencyKey()` 추가 |
| Application | `OrderCreateService.java` — 멱등성 분기 흐름 변경, `@Transactional` 제거 |
| Application | `OrderCreateProcessor.java` (신규) — 트랜잭션 분리, 이벤트 발행 |
| Application | `OrderIdempotencyCacheEvent.java` (신규) — AFTER_COMMIT 이벤트 클래스 |
| Application | `OrderErrorCode.java` — `ORDER_IDEMPOTENCY_IN_PROGRESS` 제거 |
| Infrastructure | `JpaOrderRepository.java` — `findByMemberIdAndIdempotencyKey()` 구현 |
| Infrastructure | `OrderRepositoryAdapter.java` — 위임 구현 추가 |
| Infrastructure | `RedisOrderIdempotencyStore.java` — AFTER_COMMIT 리스너 추가, Redis 장애 fallback 추가 |

## 설계 방향

**트랜잭션 분리 (`OrderCreateProcessor` 도입)**

`OrderCreateService.createOrder()`는 `@Transactional` 없이 멱등성 분기만 담당한다.
실제 재고 차감 + 주문 저장은 `OrderCreateProcessor.execute()`(`@Transactional`)에서 처리한다.
이렇게 하면 `DataIntegrityViolationException` 발생 시 해당 트랜잭션만 롤백되고,
`OrderCreateService`에서 catch 후 새 트랜잭션으로 DB 재조회가 가능하다.

**AFTER_COMMIT 이벤트 패턴 (ADR-002/005)**

`OrderCreateProcessor` 안에서 `ApplicationEventPublisher.publishEvent(OrderIdempotencyCacheEvent)`를 발행한다.
`RedisOrderIdempotencyStore`가 `@TransactionalEventListener(AFTER_COMMIT)`으로 구독하여
RDB 커밋 성공 이후에만 Redis에 `complete()`를 호출한다.
RDB rollback 시 이벤트가 발행되지 않으므로 Redis에 COMPLETED가 남지 않는다.

**Redis 장애 fallback**

`RedisOrderIdempotencyStore.reserve()`와 `getCompletedOrderId()`에서 Redis 예외를 catch하여
각각 `false`와 `Optional.empty()`를 반환한다.
`OrderCreateService`는 Redis 예외를 알 필요 없이 자연스럽게 INSERT 시도 경로로 진입한다.

## 데이터 흐름

```
createOrder()
  ├─ reserve() → true  → OrderCreateProcessor.execute()
  │                          └─ 재고 차감 + 주문 저장 + 이벤트 발행
  │                               └─ AFTER_COMMIT → Redis complete()
  │
  └─ reserve() → false
       ├─ getCompletedOrderId() → hit  → DB 조회 → 기존 주문 반환
       └─ getCompletedOrderId() → miss → OrderCreateProcessor.execute()
                                            └─ DataIntegrityViolationException
                                                 → clear() → DB 재조회 → 기존 주문 반환
```

## 예외 및 실패 처리

| 예외 | 발생 위치 | 처리 방식 |
|------|-----------|-----------|
| `DataIntegrityViolationException` | `OrderCreateProcessor.execute()` | `OrderCreateService`에서 catch → `clear()` → DB 재조회 → 기존 주문 반환 |
| `RedisException` (reserve) | `RedisOrderIdempotencyStore.reserve()` | `false` 반환 (log.warn) |
| `RedisException` (getCompletedOrderId) | `RedisOrderIdempotencyStore.getCompletedOrderId()` | `Optional.empty()` 반환 (log.warn) |

`DataIntegrityViolationException`은 CLAUDE.md 규칙에 따라 Application 계층에서 처리하고 Presentation으로 넘기지 않는다.

## 테스트 포인트

- 동일 key 재요청 → Redis COMPLETED hit → 기존 주문 반환
- TTL 만료 후 재요청 → INSERT 시도 → unique 위반 → DB 재조회 → 기존 주문 반환
- Redis 장애 시 첫 요청 → INSERT 시도 → 성공
- 동시 요청 → unique 위반 → DB 재조회 → 기존 주문 반환 (CyclicBarrier 활용)
- RDB rollback 시 Redis에 COMPLETED 저장 안 됨 (AFTER_COMMIT 패턴)
