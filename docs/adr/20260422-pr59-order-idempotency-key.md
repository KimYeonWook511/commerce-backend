# 주문 생성에 멱등 키를 적용한다 (Redis 1차 방어선 + RDB unique 제약 최종 보장)

- Status: superseded by [20260601-pr180-order-idempotency-inflight-guard](20260601-pr180-order-idempotency-inflight-guard.md)
- Date: 2026-04-22

## Context

Redis TTL 만료 후 중복 주문 생성 방지 및 Redis 장애 시에도 주문 가능성 보장.

## Decision

- 주문 생성 요청은 멱등 키를 요구하며, Redis(1차)와 RDB unique 제약(최종)으로 이중 보장한다. `idempotencyKey`는 클라이언트가 생성한 UUID이며 HTTP Header(`Idempotency-Key`)로 전달한다.
- **멱등성 처리 흐름**: Redis `reserve()` 성공 시 주문 생성 → AFTER_COMMIT 이벤트로 Redis 캐싱 (Redis 캐싱을 RDB 커밋 이후 실행하는 기존 결정(→ PR#91)의 구현). Redis MISS(TTL 만료 or Redis 장애) 시 바로 INSERT 시도 → `(member_id, idempotency_key)` unique 위반 시 기존 주문을 조회하여 `complete()`로 Redis 갱신 후 반환. 기존 주문을 찾지 못하면 멱등키 외 다른 제약 위반이므로 `log.error` 기록 후 `ORDER_NOT_FOUND` 반환.
- **Redis 장애 처리**: `reserve()`, `getCompletedOrderId()`, `complete()`, `clear()`, `handle()` 실패 시 모두 Infrastructure 계층에서 예외를 catch. `reserve()`→`false`, `getCompletedOrderId()`→`empty()` fallback으로 주문 생성 경로 진입. 나머지는 warn 로그 후 무시하여 주문 반환에 영향 없음.

## Consequences

TTL 만료 후 재요청 시 재고 차감 → unique 위반 → 롤백이 드물게 발생할 수 있다. 정확성에는 문제 없다.
