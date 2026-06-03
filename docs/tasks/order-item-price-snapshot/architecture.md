# 태스크 아키텍처

## 개요

- `OrderItem` aggregate child entity 에 `unitPrice` 컬럼을 추가하여 결제 시점 가격을 영구 보존한다.
- schema 변경 (V5 migration) 과 entity 매핑 변경이 한 쌍으로 들어간다.
- `Order` aggregate root 의 외부 시그니처 (`addOrderItem(productId, quantity, unitPrice)`) 는 그대로 유지한다. 내부에서 `OrderItem.of(...)` 로 `unitPrice` 가 흘러가도록만 정리한다.

## 변경 대상

- **Domain (main)**
  - `com.commerce.order.domain.OrderItem`: `@Column private int unitPrice` 컬럼 추가, 생성자 / `of(...)` 팩토리에 `unitPrice` 파라미터 추가, 미해결 주석 제거.
  - `com.commerce.order.domain.Order`: `addOrderItem(...)` 내부에서 `OrderItem.of(this, productId, quantity, unitPrice)` 호출. 외부 시그니처 동일.
- **Test**
  - `com.commerce.order.domain.OrderTest`: `addOrderItem_whenCalled_*` 테스트에 `unitPrice` 보존 assertion 추가. snapshot 보존 시나리오 단위 테스트 신규.
  - `com.commerce.order.infrastructure.OrderRepositoryJpaAdapterTest`: persist → reload 후 `unitPrice` round-trip 검증 보강.
- **Migration (resources)**
  - `src/main/resources/db/migration/V5__add_order_item_unit_price.sql` 신규.

## 설계 방향

- **타입**: `int`. 기존 `Order.totalPrice` / `Payment.amount` / `Product.price` 가 모두 `int` 라 통일성 확보. `Money` VO 도입은 본 task 범위 밖.
- **시그니처 유지**: `Order.addOrderItem(Long productId, int quantity, int unitPrice)` 는 PR #200 에서 이미 자리잡은 시그니처. 본 task 는 인자가 OrderItem 컬럼까지 흘러가도록만 한다. 외부 호출부 (production / test) 변경 0건.
- **`OrderItem.of` 시그니처 확장**: 외부에서 직접 `OrderItem.of` 를 호출하는 코드는 `Order.addOrderItem` 내부 1곳뿐이다. 안전하게 확장.
- **Snapshot 의미 명문화**: 본 task 가 정립하는 결정은 "결제 시점 단가를 OrderItem 에 저장하고, 이후 `Product.price` 변동에 영향받지 않는다" 이다. 이 결정을 `OrderItem.java` 의 주석으로 대체하는 게 아니라 task adr 로 관리한다.

## 데이터 흐름

```
[OrderCreateProcessor / OrderConcurrencyService]
   ↓ product = productRepository.findById(...)
   ↓ order.addOrderItem(product.getId(), qty, product.getPrice())
[Order.addOrderItem]
   ↓ OrderItem.of(this, productId, qty, unitPrice)
   ↓ this.totalPrice += unitPrice * qty
[OrderItem]
   ↓ unitPrice 컬럼에 저장 → tbl_order_item.unit_price 영속화
```

- `OrderItem.unitPrice` 는 한 번 저장되면 변경 경로가 없다 (setter 없음, 도메인 메서드 없음).
- 본 PR 에서는 응답 DTO 에 `unitPrice` 를 노출하지 않는다. snapshot 은 entity / schema 까지만.

## 마이그레이션 흐름

1. `ALTER TABLE tbl_order_item ADD COLUMN unit_price INT NULL AFTER quantity` — 우선 nullable 로 컬럼 추가.
2. `UPDATE tbl_order_item oi JOIN tbl_product p ON oi.product_id = p.id SET oi.unit_price = p.price WHERE oi.unit_price IS NULL` — 기존 row 를 product 현재가로 backfill.
3. `ALTER TABLE tbl_order_item MODIFY COLUMN unit_price INT NOT NULL` — NOT NULL 전환.

- backfill 정확도: 이미 `Product.price` 가 변동된 row 는 결제 시점 가격이 아닌 현재가로 채워진다는 한계가 있다. 본 결정의 trade-off 이며 task adr 에 명문화한다.
- `product_id` 의 FK 제약은 PR #203 (V4) 에서 제거됐다. JOIN UPDATE 는 일반 nested loop 으로 수행되며 deadlock 위험은 낮다.

## 예외 및 실패 처리

- `unitPrice` 가 `int` 이므로 음수 / 0 검증은 도메인에 추가하지 않는다 (현재 `Product.price` 자체에 검증이 있고, 본 PR 은 snapshot 의미만 추가).
- backfill 단계에서 product 가 hard-delete 된 row 가 있는 경우 `unit_price` 가 NULL 로 남아 NOT NULL 전환이 실패할 수 있다. 현재 운영 데이터에 hard-delete 가 없으므로 가드를 추가하지 않는다. 발생 시 migration 실패로 가시화되어 운영 단계에서 대응한다.

## 테스트 포인트

- 단위
  - `OrderTest`: `addOrderItem(productId, qty, unitPrice)` 호출 후 `OrderItem.getUnitPrice()` 가 인자 그대로 보존된다.
  - `OrderTest`: 같은 `productId` 로 두 번 addOrderItem 한 뒤 두 번째 호출의 `unitPrice` 가 첫 번째의 값을 덮지 않는다 (snapshot 보존 시나리오).
- 슬라이스
  - `OrderRepositoryJpaAdapterTest`: persist → flush → clear → reload 후 `OrderItem.unitPrice` 가 DB round-trip 된다.
- 통합 (`integrationTest`)
  - Testcontainers MySQL 에 V5 까지 적용 후 Hibernate `validate` 통과.
