# 태스크 ADR

## 결정 1: `OrderItem.unitPrice` 를 `int` 컬럼으로 추가하여 결제 시점 가격을 보존한다

### 배경

- PR #200 (`refactor: Order·OrderItem JPA 연관관계 분리`) 에서 `Order.addOrderItem` 시그니처가 `(Long productId, int quantity, int unitPrice)` 로 전환됐다. `unitPrice` 는 도메인에 흘러 들어왔지만 `OrderItem` 컬럼이 아닌 `Order.totalPrice` 누적 결과로만 남아 결제 시점 가격이 휘발한다.
- e-commerce 표준은 결제 시점 가격을 OrderItem 에 snapshot 하여 `Product.price` 변동이 영수증 / 환불 / 정산 / 통계에 영향을 주지 않게 하는 것이다.
- `OrderItem.java` 의 미해결 주석 (`// 가격도 넣어야 하나? ...`) 이 같은 분기를 가리키고 있었지만 series #195 의 "schema 변경 0건" 메타 원칙으로 컬럼 신설을 보류했다 (`docs/tasks/order-jpa-association-decouple/adr.md` 결정 3).
- series 종료 후 후속 트랙으로 본 task 가 분리됐다.

### 결정 내용

- `tbl_order_item` 에 `unit_price INT NOT NULL` 컬럼을 추가한다 (Flyway V5).
- `OrderItem` entity 에 `@Column private int unitPrice` 필드를 추가하고, 생성자 / `OrderItem.of(...)` 팩토리에 `unitPrice` 인자를 추가한다.
- `Order.addOrderItem(...)` 의 외부 시그니처는 변경하지 않고, 내부에서 `OrderItem.of(this, productId, quantity, unitPrice)` 로 인자를 전달한다.
- `OrderItem.java` 의 미해결 주석은 제거한다.

### 근거

- **시그니처 보존**: PR #200 에서 자리잡은 `addOrderItem(productId, quantity, unitPrice)` 호출부가 production 3곳 / test 10여 곳 있다. 외부 시그니처를 그대로 두면 본 PR 의 변경 영향이 entity / migration / 단위 테스트 assertion 보강에만 국한된다.
- **`int` 채택**: 기존 `Order.totalPrice`, `Payment.amount`, `Product.price` 가 모두 `int` 다. `Money` VO 도입은 영향 범위가 totalPrice / amount 까지 번져 사실상 별도 series 가 된다. 본 task 범위 밖.
- **snapshot 의미 명문화**: 미해결 주석을 코드에 남기지 않고 task adr 로 결정을 관리한다. 향후 가격 정책 변경 시 본 결정과의 상호작용을 명확히 검토 가능하다.

### 결과

- 결제 시점 가격이 `OrderItem.unitPrice` 컬럼에 영구 보존된다. `Product.price` 변동이 기존 주문의 단가에 영향을 주지 않는다.
- 호출부 영향 0건. PR 의 코드 변경 범위가 entity 1개 / aggregate root 내부 1줄 / 단위 테스트 assertion 보강 으로 줄어든다.
- trade-off: `Money` VO 도입은 별도 트랙으로 계속 미룬다.

## 결정 2: 기존 row 의 `unit_price` 는 `tbl_product.price` JOIN 으로 backfill 한다

### 배경

- `unit_price` 신규 컬럼을 NOT NULL 로 두려면 기존 row 에 값을 채워야 한다.
- 본 정비의 의도는 "결제 시점 가격 보존" 이지만, 기존 row 에 대해서는 결제 시점 가격을 더 이상 재구성할 수 없다 (애초에 그게 본 정비의 동기다).

### 결정 내용

- V5 migration 에서 컬럼을 nullable 로 추가한 뒤 `UPDATE tbl_order_item oi LEFT JOIN tbl_product p ON oi.product_id = p.id SET oi.unit_price = COALESCE(p.price, 0) WHERE oi.unit_price IS NULL` 로 backfill 한다.
- product 가 존재하는 row 는 product 현재가로 채우고, product 가 hard-delete 된 row 는 `0` 으로 fallback 한다.
- 그 후 `MODIFY COLUMN unit_price INT NOT NULL` 로 NOT NULL 전환한다.

### 근거

- 결제 시점 가격이 휘발한 이상 어떤 값으로도 정확성을 보장할 수 없다. `0` 으로 일괄 채우는 것보다 "그럴듯한 추정값" (Product 현재가) 이 향후 운영 통계에서 덜 오해를 부른다.
- 다만 `product_id` 의 FK 는 PR #203 (V4) 에서 제거된 상태라 product 가 hard-delete 됐을 가능성을 schema 차원에서 막아주지 않는다. INNER JOIN 으로 backfill 하면 hard-delete 된 row 의 `unit_price` 가 NULL 로 남아 마지막 `MODIFY ... NOT NULL` 단계에서 migration 이 실패할 수 있다.
- 따라서 LEFT JOIN + `COALESCE(p.price, 0)` 로 fallback 하여 migration 안정성을 보장한다. `0` 으로 채워진 row 는 "product 가 존재하지 않아 결제 시점 가격을 재구성할 수 없음" 의 sentinel 역할을 하며, 0 이라는 의미상 비현실적인 값으로 인해 후속 통계 / 영수증 사용처에서 이상치로 잡힌다.
- `product_id` 의 FK 는 PR #203 (V4) 에서 제거됐다. JOIN UPDATE 는 일반 nested loop 으로 수행되어 deadlock 위험이 낮다.

### 결과

- 기존 row 도 NOT NULL 제약을 만족하면서 "현재가" 또는 (product 가 없는 경우) `0` sentinel 로 채워진다.
- 신규 row 부터는 결제 시점 가격이 정확히 보존된다 — 본 결정이 보장하는 핵심.
- product hard-delete 된 row 가 있어도 migration 이 실패하지 않는다. 운영 안정성 우선.
- trade-off: 기존 row 의 `unit_price` 는 "결제 시점이 아니라 migration 시점의 product 현재가 (또는 product 부재 시 0)" 라는 의미라 통계 / 영수증 등 사용처가 생기면 row 의 `created_at` 과 migration 적용 시점, 그리고 `unit_price = 0` sentinel 가능성을 함께 봐야 정확히 해석할 수 있다. 현재 사용처가 없으므로 본 PR 에서는 이슈가 되지 않는다.

## 결정 3: 응답 DTO 노출은 본 task 범위 밖이다

### 배경

- 이슈 #201 의 작업 범위에 "결제 응답 / 영수증 등 unitPrice 노출이 필요한 응답 DTO 가 있다면 함께 정비" 가 적혀 있었다.
- 현재 코드베이스에 `OrderItem` 을 직접 노출하는 응답 DTO 가 없다 (`OrderCreateResult` / `OrderCancelResult` 는 OrderItem 자체를 노출하지 않는다). `PaymentReadyService` 도 `order.getTotalPrice()` 만 쓴다.

### 결정 내용

- 본 PR 에서는 응답 DTO / API spec 을 수정하지 않는다.
- 실제 소비처 (예: 주문 상세 조회, 영수증 응답) 가 생기는 시점에 별도 PR 로 다룬다.

### 근거

- 노출 사용처가 없는 상태에서 DTO 를 미리 만들면 사용처 없는 필드가 늘어난다.
- 본 task 의 핵심 목적은 entity / schema 차원의 snapshot 보존이지 응답 노출이 아니다.

### 결과

- 본 PR 의 변경 범위가 명확히 entity / migration / 도메인 단위 테스트에 한정된다.
- 응답 DTO 노출은 별도 PR 로 다루며, 그때 본 결정과 함께 검토한다.
