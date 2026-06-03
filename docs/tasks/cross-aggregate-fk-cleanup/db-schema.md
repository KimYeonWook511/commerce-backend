# 태스크 DB 스키마

## 개요

- 본 태스크는 **DB schema 변경이 핵심 산출물**이다. Flyway migration 1개 (`V4__drop_cross_aggregate_fk_constraints.sql`) 추가로 cross-aggregate FK 5건을 일괄 제거한다.
- UNIQUE 제약, 동명 KEY index, same-aggregate FK 는 모두 유지한다.

## 신규 테이블

- 없음.

## 변경 테이블

### `tbl_stock`

- 제거: `CONSTRAINT fk_stock_product_id FOREIGN KEY (product_id) REFERENCES tbl_product (id)`
- 유지: `UNIQUE KEY uk_stock_product_id (product_id)` — Stock 1:1 Product 도메인 invariant.

### `tbl_stock_history`

- 제거: `CONSTRAINT fk_stock_history_stock_id FOREIGN KEY (stock_id) REFERENCES tbl_stock (id)`
- 유지: `KEY fk_stock_history_stock_id (stock_id)` — 조회 보조 index. FK DROP 후 동명 KEY 그대로 둔다.

### `tbl_order`

- 제거: `CONSTRAINT fk_order_member_id FOREIGN KEY (member_id) REFERENCES tbl_member (id)`
- 유지: `UNIQUE KEY uk_order_member_idempotency (member_id, idempotency_key)`, `UNIQUE KEY uk_order_merchant_pay_key`. InnoDB 가 FK 위해 자동 생성한 index 는 잔류해도 그대로 둔다.

### `tbl_order_item`

- 제거: `CONSTRAINT fk_order_item_product_id FOREIGN KEY (product_id) REFERENCES tbl_product (id)`
- 유지: `KEY fk_order_item_product_id (product_id)` — 조회 보조 index.
- 유지 (범위 밖): `CONSTRAINT fk_order_item_order_id FOREIGN KEY (order_id) REFERENCES tbl_order (id)` — Order ↔ OrderItem same-aggregate FK.

### `tbl_payment`

- 제거: `CONSTRAINT fk_payment_order_id FOREIGN KEY (order_id) REFERENCES tbl_order (id)`
- 유지: `UNIQUE KEY uk_payment_order_id (order_id)` — Payment 1:1 Order 도메인 invariant.
- 유지: `UNIQUE KEY uk_payment_merchant_pay_key`, `UNIQUE KEY uk_payment_pg_payment_id` — 본 태스크 범위 밖.

## 인덱스

- 본 태스크에서 인덱스를 명시적으로 DROP 하지 않는다. UNIQUE index 는 모두 유지하고, FK 와 동명 KEY index 는 조회 보조용으로 유지한다.

## 데이터 무결성

### 제거되는 schema 제약

| 테이블 | FK 이름 | 참조 | 정책 목적 |
|---|---|---|---|
| `tbl_stock` | `fk_stock_product_id` | `tbl_product.id` | Stock ↔ Product cross-aggregate |
| `tbl_stock_history` | `fk_stock_history_stock_id` | `tbl_stock.id` | StockHistory ↔ Stock cross-aggregate (audit aggregate 분리) |
| `tbl_order` | `fk_order_member_id` | `tbl_member.id` | Order ↔ Member cross-aggregate |
| `tbl_order_item` | `fk_order_item_product_id` | `tbl_product.id` | OrderItem ↔ Product cross-aggregate |
| `tbl_payment` | `fk_payment_order_id` | `tbl_order.id` | Payment ↔ Order cross-aggregate |

### 유지되는 schema 제약

- UNIQUE: `uk_stock_product_id`, `uk_payment_order_id` — 1:1 도메인 invariant.
- same-aggregate FK: `fk_order_item_order_id` — ADR-020 범위 밖.
- 그 외 모든 컬럼 (nullable / type / default) 은 변경 없음.

### Application 차원의 정합성 방어

- FK 제거 후 cross-aggregate referential integrity 보증은 application 검증으로 이동한다.
- 선행 series 가 이미 다음 검증을 가지고 있다:
  - `productRepository.findById(productId).orElseThrow(PRODUCT_NOT_FOUND)` (`OrderCreateService` / `StockAdjustService` / `CartItemAddService`)
  - `orderRepository.findByMerchantPayKeyForUpdate(...).orElseThrow(ORDER_NOT_FOUND)` (`PaymentApprovalService`)
  - `memberRepository.findById(memberId).orElseThrow(MEMBER_NOT_FOUND)` (인증 흐름)
- cart 도메인은 ADR-020 적용 이후 application 검증만으로 정합성을 유지해온 선행 사례 (`docs/tasks/cart/adr.md` 결정 6-5).

## 마이그레이션 고려사항

- **배포 순서**: 본 PR 머지 → local / CI / test container 에서 즉시 Flyway V4 적용. 운영 DB 적용은 별도 결정 (ADR 결정 4).
- **백필**: 불필요.
- **롤백**: V4 의 역방향 SQL 은 본 PR 에 포함하지 않는다. 운영 배포 단계에서 별도 결정 시점에 운영 절차의 일부로 다룬다 (Flyway 의 `undo` 는 Community 미지원이므로, 필요 시 보정 V 파일로 재생성).
- **운영 lock 영향**: MySQL 의 `ALTER TABLE ... DROP FOREIGN KEY` 는 InnoDB 에서 일반적으로 짧은 metadata lock 만 잡고 끝난다 (대규모 데이터 복사 없음). 다만 운영 DB 의 동시 트랜잭션 / replication topology / 정비 윈도우는 운영 결정 단계에서 점검.
