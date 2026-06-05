# Step 1: add-unit-price-snapshot

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/order-item-price-snapshot/prd.md`
- `/docs/tasks/order-item-price-snapshot/architecture.md`
- `/docs/tasks/order-item-price-snapshot/adr.md`
- `/docs/tasks/order-item-price-snapshot/api-spec.md`
- `/docs/tasks/order-item-price-snapshot/db-schema.md`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/db-schema.md` (`tbl_order_item` 섹션, V4 비고)
- `/docs/adr.md` (Task ADR 색인 / ADR-024 Flyway / ADR-020 cross-aggregate)
- `/docs/tasks/order-jpa-association-decouple/retrospective.md` ("결제 시점 가격 snapshot 미해결" 항목, 직접 동기)
- `/docs/tasks/order-jpa-association-decouple/adr.md` (시그니처 / schema 무변경 원칙)

현재 schema 와 entity 매핑 상태를 파악하기 위해 아래 파일도 읽는다.

- `/src/main/resources/db/migration/V1__init.sql` (`tbl_order_item` 정의, line 79~94)
- `/src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql` (`fk_order_item_product_id` 가 이미 제거된 상태)
- `/src/main/java/com/commerce/order/domain/OrderItem.java`
- `/src/main/java/com/commerce/order/domain/Order.java`
- `/src/main/java/com/commerce/order/application/OrderCreateProcessor.java` (호출부, line 79)
- `/src/main/java/com/commerce/order/application/OrderConcurrencyService.java` (호출부, line 109 / 135)
- `/src/test/java/com/commerce/order/domain/OrderTest.java`
- `/src/test/java/com/commerce/order/infrastructure/OrderRepositoryJpaAdapterTest.java`

## 작업

`OrderItem` aggregate child entity 에 `unitPrice` 컬럼을 추가하여 결제 시점 가격을 영구 보존한다. schema 변경 (V5 migration) 과 entity 매핑 변경, 단위 / 슬라이스 테스트 보강을 한 step 으로 묶는다. `Order.addOrderItem` 외부 시그니처는 변경하지 않는다.

### Flyway migration 추가

- 신규 파일: `src/main/resources/db/migration/V5__add_order_item_unit_price.sql`
- 내용:

```sql
-- OrderItem 에 결제 시점 가격 snapshot 컬럼을 추가한다.
-- 기존 row 는 tbl_product.price (현재가) 로 backfill 후 NOT NULL 로 전환한다.
-- product 가 hard-delete 된 row 는 LEFT JOIN + COALESCE 로 0 fallback 한다.
-- backfill 정확도의 한계와 0 fallback 의미는 docs/tasks/order-item-price-snapshot/adr.md 결정 2 에 명문화돼 있다.

ALTER TABLE `tbl_order_item` ADD COLUMN `unit_price` INT NULL AFTER `quantity`;

UPDATE `tbl_order_item` oi
LEFT JOIN `tbl_product` p ON oi.product_id = p.id
SET oi.unit_price = COALESCE(p.price, 0)
WHERE oi.unit_price IS NULL;

ALTER TABLE `tbl_order_item` MODIFY COLUMN `unit_price` INT NOT NULL;
```

SQL 작성 규칙:
- backtick (`` ` ``) 으로 식별자 감싸기 — V1 / V4 의 컨벤션과 일관.
- SQL 3개 (ADD COLUMN, UPDATE backfill with LEFT JOIN + COALESCE, MODIFY COLUMN NOT NULL) 만 포함. 다른 ALTER / DROP / CREATE / INDEX 명령을 추가하지 마라.
- 다른 V 파일을 만들지 마라. 본 task 의 정책 단위는 단일 V 파일.

### Domain entity 수정

**`src/main/java/com/commerce/order/domain/OrderItem.java`**

- `@Column(nullable = false) private int unitPrice` 필드를 `quantity` 아래에 추가한다.
- 생성자 시그니처를 `private OrderItem(Order order, Long productId, int quantity, int unitPrice)` 로 확장하고 `this.unitPrice = unitPrice` 를 추가한다.
- 팩토리 메서드 시그니처를 `public static OrderItem of(Order order, Long productId, int quantity, int unitPrice)` 로 확장한다.
- 49~50행의 미해결 주석 (`// 가격도 넣어야 하나? ...`, `// 추후 고려하기`) 을 제거한다. 결정은 task adr 에 명문화돼 있다.

**`src/main/java/com/commerce/order/domain/Order.java`**

- `addOrderItem(Long productId, int quantity, int unitPrice)` 내부에서 `OrderItem orderItem = OrderItem.of(this, productId, quantity, unitPrice);` 로 호출하도록 인자 1개를 추가한다.
- `addOrderItem` 의 외부 시그니처는 그대로 유지한다. `this.totalPrice += unitPrice * quantity` 라인도 그대로.

### 테스트 보강

**`src/test/java/com/commerce/order/domain/OrderTest.java`**

- 기존 `addOrderItem_whenCalled_appendOrderItemAndIncreaseTotalPrice` 테스트의 then 블록에 `assertThat(order.getOrderItems().get(0).getUnitPrice()).isEqualTo(1500)` assertion 을 추가한다.
- 신규 테스트 추가: `addOrderItem_whenMultipleItemsWithDifferentUnitPrice_preserveEachUnitPrice`
  - given: `Order.create(1L)`
  - when: 같은 `productId` 로 서로 다른 `unitPrice` 두 번 호출 (예: `order.addOrderItem(10L, 1, 1000)` → `order.addOrderItem(10L, 1, 2000)`)
  - then: 두 OrderItem 의 `unitPrice` 가 각각 1000 / 2000 으로 보존된다 (둘째 호출이 첫째를 덮어쓰지 않는다 — snapshot 보존 시나리오).
  - `@DisplayName` 한국어 설명을 붙인다.

**`src/test/java/com/commerce/order/infrastructure/OrderRepositoryJpaAdapterTest.java`**

- 기존 persist → reload 시나리오 (line 52 / 163 근방) 의 assertion 에 `OrderItem.unitPrice` 가 `product.getPrice()` 와 같음을 추가 검증한다. 새 메서드를 추가하지 말고 기존 테스트의 then 블록만 보강한다.

### 호출부 / 응답 DTO

- `OrderCreateProcessor:79`, `OrderConcurrencyService:109/135` 은 이미 `product.getPrice()` 를 `unitPrice` 로 넘기고 있으므로 수정하지 마라.
- `OrderCreateResult`, `OrderCancelResult`, `PaymentReadyService` 는 OrderItem.unitPrice 를 직접 사용하지 않으므로 수정하지 마라 (task adr 결정 3).

## 수정 가능 경로

- `src/main/resources/db/migration/V5__add_order_item_unit_price.sql` (신규 파일만)
- `src/main/java/com/commerce/order/domain/OrderItem.java`
- `src/main/java/com/commerce/order/domain/Order.java`
- `src/test/java/com/commerce/order/domain/OrderTest.java`
- `src/test/java/com/commerce/order/infrastructure/OrderRepositoryJpaAdapterTest.java`
- `docs/tasks/order-item-price-snapshot/**` (필요 시 보정)

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - V5 SQL 이 `ADD COLUMN unit_price`, `UPDATE ... JOIN tbl_product`, `MODIFY COLUMN unit_price ... NOT NULL` 3개 statement 를 포함하는가?
     - `grep -c "unit_price" src/main/resources/db/migration/V5__add_order_item_unit_price.sql` 결과 ≥ 3.
   - V5 가 다른 테이블이나 다른 컬럼을 건드리지 않는가?
     - `grep -E "tbl_(order|payment|stock|member)" src/main/resources/db/migration/V5__add_order_item_unit_price.sql` 결과 `tbl_order_item`, `tbl_product` 만 나와야 한다.
   - `OrderItem.java` 에서 미해결 주석이 제거됐는가?
     - `grep "가격도 넣어야 하나" src/main/java/com/commerce/order/domain/OrderItem.java` 결과 0건.
   - `OrderItem.java` 에 `unitPrice` 필드가 있는가?
     - `grep -c "private int unitPrice" src/main/java/com/commerce/order/domain/OrderItem.java` 결과 1.
   - `Order.addOrderItem` 외부 시그니처가 변경되지 않았는가?
     - `grep "public void addOrderItem" src/main/java/com/commerce/order/domain/Order.java` 결과가 `addOrderItem(Long productId, int quantity, int unitPrice)` 형태로 유지된다.
   - `OrderTest` 가 snapshot 보존 시나리오 신규 테스트를 포함하는가?
     - `grep "preserveEachUnitPrice\|보존\|snapshot" src/test/java/com/commerce/order/domain/OrderTest.java` 결과 ≥ 1.
   - `integrationTest` 가 Testcontainers MySQL 에 V5 까지 적용하고 Hibernate `validate` 를 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `Order.addOrderItem(Long, int, int)` 외부 시그니처를 변경하지 마라. 이유: production 호출 3곳 / test 호출 10여 곳이 의존한다. 본 task 의 영향 범위 차단 원칙 (task adr 결정 1).
- `Order.totalPrice` 누적 로직을 변경하지 마라. 이유: 본 task 는 snapshot 보존만 추가하고 누적 의미는 그대로 유지한다.
- 응답 DTO (`OrderCreateResult`, `OrderCancelResult`) 나 `PaymentReadyService` 를 수정하지 마라. 이유: task adr 결정 3 — 응답 DTO 노출은 별도 PR.
- `Money` VO 를 도입하지 마라. 이유: 영향 범위가 `totalPrice` / `Payment.amount` 까지 번져 별도 series 가 된다. task adr 결정 1.
- `unit_price` 컬럼에 인덱스를 추가하지 마라. 이유: 조회 조건으로 사용하지 않는다.
- V1 / V2 / V3 / V4 기존 Flyway 파일을 수정하지 마라. 이유: 이미 적용된 migration 은 immutable. 변경은 항상 새 V 파일로 표현한다.
- `OrderItem.unitPrice` 에 setter / 변경 도메인 메서드를 추가하지 마라. 이유: snapshot 의미상 한 번 저장되면 변경 경로가 없어야 한다.
- 기존 test fixture 의 `addOrderItem(...)` 호출에서 unitPrice 인자 값을 수정하지 마라. 이유: 시그니처가 동일하므로 호출 형태를 그대로 둔다. fixture 의도를 임의로 바꾸지 않는다.
- 머지된 task 폴더 (`docs/tasks/order-jpa-association-decouple/*`, `docs/tasks/payment-jpa-association-decouple/*`, `docs/tasks/cross-aggregate-fk-cleanup/*`) 의 문서를 수정하지 마라. 이유: 완료 task 폴더 불변 원칙.
- 기존 테스트를 깨뜨리지 마라.
