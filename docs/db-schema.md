# DB 스키마

## 네이밍 규칙

- 테이블명: `tbl_<domain>`
- 일반 인덱스: `idx_<target>_<columns>`
- 유니크 키/유니크 인덱스: `uk_<target>_<columns>`

예시:
- `tbl_member`
- `tbl_payment_attempt`
- `idx_outbox_event_type_status_next_retry_id`
- `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`

## 테이블 요약

### `tbl_member`

COLUMNS:
- `id (PK)`
- `email (UNIQUE)`
- `password`
- `username`
- `role`

INDEX:
- `email (UNIQUE)`

### `tbl_product`

COLUMNS:
- `id (PK)`
- `name`
- `price`
- `description`
- `image_url`
- `status`
- `deleted_at`

INDEX:
- 없음

### `tbl_stock`

COLUMNS:
- `id (PK)`
- `version`
- `product_id (FK -> tbl_product.id, UNIQUE)`
- `quantity`

INDEX:
- `product_id (UNIQUE)`

### `tbl_order`

COLUMNS:
- `id (PK)`
- `version`
- `member_id (FK -> tbl_member.id)`
- `total_price`
- `status`
- `merchant_pay_key (UNIQUE)`

INDEX:
- `merchant_pay_key (UNIQUE)`

### `tbl_order_item`

COLUMNS:
- `id (PK)`
- `order_id (FK -> tbl_order.id)`
- `product_id (FK -> tbl_product.id)`
- `quantity`

INDEX:
- 없음

### `tbl_payment`

COLUMNS:
- `id (PK)`
- `order_id (FK -> tbl_order.id, UNIQUE)`
- `amount`
- `status`
- `provider`
- `merchant_pay_key (UNIQUE)`
- `pg_payment_id (UNIQUE)`
- `approved_at`

INDEX:
- `order_id (UNIQUE)`
- `merchant_pay_key (UNIQUE)`
- `pg_payment_id (UNIQUE)`

### `tbl_payment_attempt`

COLUMNS:
- `id (PK)`
- `merchant_pay_key`
- `payment_id`
- `amount`
- `provider`
- `type`
- `status`
- `fail_code`
- `fail_detail`
- `responded_at`

INDEX:
- `uk_payment_attempt_merchant_pay_key_provider_payment_id_type (merchant_pay_key, provider, payment_id, type) UNIQUE`

### `tbl_outbox_event`

COLUMNS:
- `id (PK)`
- `event_id (UNIQUE)`
- `event_type`
- `payload`
- `status`
- `attempt_count`
- `next_retry_at`
- `published_at`
- `last_error`
- `aggregate_type`
- `aggregate_id`

INDEX:
- `event_id (UNIQUE)`
- `idx_outbox_event_type_status_next_retry_id (event_type, status, next_retry_at, id)`

### `tbl_processed_event`

COLUMNS:
- `id (PK)`
- `event_id`
- `consumer_type`

INDEX:
- `idx_processed_event_event_id_consumer_type (event_id, consumer_type) UNIQUE`

## 주요 관계

- `tbl_member` 1:N `tbl_order`
- `tbl_order` 1:N `tbl_order_item`
- `tbl_product` 1:1 `tbl_stock`
- `tbl_product` 1:N `tbl_order_item`
- `tbl_order` 1:1 `tbl_payment`
