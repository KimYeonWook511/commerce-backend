# 기능 ADR

> **본 결정은 `order-idempotency-cache-simplification` 으로 대체됨.** 새 결정은 [해당 task adr](../order-idempotency-cache-simplification/adr.md) 참조.

## ADR-001: 멱등성 보장을 Redis 단독에서 Redis + RDB 이중 구조로 전환한다

### 배경

현재 Redis SETNX 기반 멱등성 구현은 세 가지 문제가 있다:
1. `complete()` 호출이 `@Transactional` 안에 있어 RDB rollback 시 Redis에 COMPLETED가 남는다.
2. RDB 커밋 성공 후 Redis 저장 실패 시 다음 재요청이 중복 주문을 생성할 수 있다.
3. Redis TTL 만료 이후 동일 key 재시도 시 중복 주문이 생성될 수 있다.

### 결정 내용

- Redis: 1차 방어선 유지 (`reserve()`로 중복 요청 차단, DB 부하 감소)
- RDB `(member_id, idempotency_key)` unique 제약: 최종 멱등성 보장
- `complete()` 호출을 AFTER_COMMIT 이벤트로 분리 (ADR-002/005 구현)
- 동일 key + 다른 payload → 기존 주문 반환 (request_hash는 추후 검토)

### 근거

- Redis 장애 시에도 주문 생성이 가능해야 한다. RDB가 최종 보장하므로 Redis 없이도 멱등성이 유지된다.
- TTL 만료는 구조적으로 막을 수 없으므로 RDB 레벨에서 중복을 방지해야 한다.
- AFTER_COMMIT 패턴은 ADR-005에서 이미 정의된 기준이며, Redis 장애가 RDB rollback을 유발하지 않도록 한다.

### 결과

- Redis 장애 시에도 주문 생성 가능 (RDB fallback)
- TTL 만료 후 재요청 시에도 중복 주문 생성 없음
- RDB rollback 시 Redis에 COMPLETED가 남는 불일치 해소
- `ORDER_IDEMPOTENCY_IN_PROGRESS` 예외 제거 (더 이상 발생 경로 없음)
- `DataIntegrityViolationException` 발생 후 기존 주문 확인 시 `complete()`로 Redis 갱신 → 이후 재요청은 DB 조회 없이 Redis hit 처리
- trade-off: `DataIntegrityViolationException` 발생 시 재고 차감 → rollback이 드물게 발생할 수 있음 (정확성에는 문제 없음)

## ADR-002: 멱등성 처리를 위해 OrderCreateProcessor를 별도 Bean으로 분리한다

### 배경

`DataIntegrityViolationException` catch 후 DB 재조회가 같은 트랜잭션에서 불가능하다.
트랜잭션이 rollback-only 상태가 되므로 catch 이후 조회 작업에 새 트랜잭션이 필요하다.

### 결정 내용

- `OrderCreateService.createOrder()`: `@Transactional` 없음, 멱등성 분기만 담당
- `OrderCreateProcessor.execute()`: `@Transactional`, 재고 차감 + 주문 저장 + 이벤트 발행

### 근거

같은 클래스 내 self-invocation은 Spring AOP가 적용되지 않으므로 별도 Bean 분리가 필요하다.
`OrderCreateProcessor`는 트랜잭션 실행 전담 내부 helper이며 외부에서 직접 호출하지 않는다.

### 결과

- `DataIntegrityViolationException` catch 후 새 트랜잭션으로 DB 재조회 가능
- 멱등성 로직과 트랜잭션 로직의 책임이 명확히 분리됨
