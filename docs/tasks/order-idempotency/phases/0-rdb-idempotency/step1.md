# Step 1: rdb-idempotency-base

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/features/order-idempotency/prd.md`
- `docs/features/order-idempotency/architecture.md`
- `docs/features/order-idempotency/adr.md`
- `docs/features/order-idempotency/db-schema.md`
- `docs/commit-conventions.md`
- `src/main/java/com/commerce/order/domain/Order.java`
- `src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `src/main/java/com/commerce/order/infrastructure/JpaOrderRepository.java`
- `src/main/java/com/commerce/order/infrastructure/OrderRepositoryAdapter.java`

## 작업

`tbl_order`에 `idempotency_key` 컬럼과 복합 unique 제약을 추가하고, 이를 지원하는 도메인 및 인프라 코드를 변경한다.

### 1. `Order.java` — `idempotencyKey` 필드 추가 및 오버로드

`@Table` 어노테이션에 uniqueConstraints를 추가하여 `(member_id, idempotency_key)` 복합 unique 제약을 선언한다.

```java
@Table(name = "tbl_order", uniqueConstraints = {
    @UniqueConstraint(name = "uk_order_member_idempotency", columnNames = {"member_id", "idempotency_key"})
})
```

`idempotencyKey` 필드를 추가한다:
```java
@Column
private String idempotencyKey;
```

기존 `create(Member member)` 오버로드는 반드시 유지한다. `OrderConcurrencyService` 등 멱등성 없는 경로에서 사용 중이다.
멱등성 포함 버전을 오버로드로 추가한다:
```java
public static Order create(Member member, String idempotencyKey) { ... }
```

### 2. `OrderRepository.java` (domain) — 메서드 추가

```java
Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);
```

### 3. `JpaOrderRepository.java` — JPA 쿼리 구현

```java
Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);
```
Spring Data JPA 메서드 네이밍으로 구현하거나 `@Query`를 사용한다.

### 4. `OrderRepositoryAdapter.java` — 위임 구현

`findByMemberIdAndIdempotencyKey()`를 `JpaOrderRepository`에 위임하여 구현한다.

## 수정 가능 경로

- `src/main/java/com/commerce/order/domain/Order.java`
- `src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `src/main/java/com/commerce/order/infrastructure/JpaOrderRepository.java`
- `src/main/java/com/commerce/order/infrastructure/OrderRepositoryAdapter.java`
- `docs/features/order-idempotency/**`

## Acceptance Criteria

```bash
./gradlew test
```

entity 계약 변경(builder/constructor 오버로드 추가)이 포함되므로 전체 테스트로 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. `Order.create(member)` 기존 호출이 모두 컴파일 오류 없이 유지되는지 확인한다:
   ```bash
   grep -rn "Order.create(" src/
   ```
3. architecture.md 디렉토리 구조를 따르는가?
4. ADR 기술 스택을 벗어나지 않았는가?
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `Order.create(Member member)` 기존 오버로드를 제거하거나 시그니처를 변경하지 마라. 이유: `OrderConcurrencyService`, 테스트 코드 등 기존 호출자가 다수 존재하며 이번 step 범위가 아니다.
- 기존 테스트를 깨뜨리지 마라.
- `OrderCreateService` 로직을 이 step에서 변경하지 마라. 이유: step1에서 처리한다.
