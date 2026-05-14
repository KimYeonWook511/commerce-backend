# 기능 PRD

## 기능명

- `order-idempotency`

## 배경

현재 주문 생성 멱등성은 Redis SETNX 기반으로 구현되어 있다.
Redis는 RDB 트랜잭션에 참여하지 않으므로 RDB 커밋과 Redis 저장 사이의 원자성이 보장되지 않는다.
또한 Redis TTL 만료 이후 동일 키로 재시도하면 중복 주문이 생성될 수 있다.
관련 이슈: #72

## 목표

- 주문 생성 멱등성을 RDB unique 제약으로 최종 보장한다.
- Redis 장애 상황에서도 주문 생성이 정상적으로 동작한다.
- RDB 커밋/롤백이 Redis 상태와 독립적으로 동작한다.

## 범위

포함 범위:
- `tbl_order`에 `idempotency_key` 컬럼 및 `(member_id, idempotency_key)` unique 제약 추가
- Redis를 1차 방어선으로 유지하되 RDB를 최종 보장 수단으로 추가
- `complete()` 호출을 AFTER_COMMIT 이벤트로 분리 (ADR-002/005 구현)
- Redis 장애 시 RDB 기반 fallback 처리

제외 범위:
- payload(items) 검증 — 동일 idempotency key면 기존 주문 반환 (request_hash는 추후 검토)
- 주문 조회/취소 API의 멱등성 처리

## 주요 시나리오

1. **정상 요청**: `reserve()` 성공 → 주문 생성 → AFTER_COMMIT에서 Redis 캐싱
2. **중복 요청 (Redis 캐시 hit)**: `reserve()` 실패 → COMPLETED 조회 → 기존 주문 반환
3. **TTL 만료 후 재요청**: `reserve()` 실패 → COMPLETED 없음 → 바로 INSERT 시도 → unique 위반 → clear() → DB 재조회 → 기존 주문 반환
4. **Redis 장애 시 첫 요청**: `reserve()` 예외 → fallback false → COMPLETED 없음 → INSERT 시도 → 성공
5. **Redis 장애 시 재요청**: `reserve()` 예외 → fallback false → COMPLETED 없음 → INSERT 시도 → unique 위반 → clear() → DB 재조회 → 기존 주문 반환
6. **동시 요청**: 두 요청이 동시에 INSERT 시도 → 하나는 unique 위반 → clear() → DB 재조회 → 기존 주문 반환

## 요구사항

- 주문 생성 시 `idempotency_key`가 RDB에 저장된다.
- 동일 회원 + 동일 idempotency key 재요청 시 기존 주문을 반환한다.
- Redis 장애와 무관하게 RDB commit/rollback이 독립적으로 동작한다.
- RDB rollback 시 Redis에 COMPLETED가 저장되지 않는다.
- 동일 idempotency key + 다른 payload → 기존 주문 반환 (payload 검증 없음)

## 제약사항

- `idempotency_key`는 기존 데이터 및 `OrderConcurrencyService` 경로와의 호환을 위해 NULL 허용
- Redis의 1차 선점 역할(`reserve()`, `clear()`)은 그대로 유지
- `order.idempotency.ttl-seconds` 설정 유지
