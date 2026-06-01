# DB 스키마

## 마이그레이션

DB 스키마 변경은 Flyway 마이그레이션 스크립트로 관리한다 (ADR-024).

- 위치: `src/main/resources/db/migration/`
- 네이밍: `V{번호}__{snake_case_설명}.sql`
- 본 문서는 테이블/컬럼/제약의 의도를 설명하는 reference이고, 실제 DDL은 `V*__*.sql`이 단일 출처다.
- 엔티티(@Entity) 변경 PR은 같은 PR에서 대응되는 V 스크립트를 함께 작성한다. ddl-auto: validate라 누락 시 부팅 실패.
- 적용된 V 스크립트는 수정하지 말고 새 V로 보정한다 (Flyway checksum).

## 네이밍 규칙

- 테이블명: `tbl_<domain>`
- 일반 인덱스: `idx_<target>_<columns>`
- 유니크 키/유니크 인덱스: `uk_<target>_<columns>`
- 외래 키: `fk_<source_table>_<source_columns>`

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

### `tbl_stock_history`

COLUMNS:
- `id (PK)`
- `stock_id (FK -> tbl_stock.id)`
- `quantity_change`
- `reason`
- `admin_member_id`
- `created_at`
- `updated_at`

INDEX:
- 없음

비고:
- 상품별 재고 이력 최신순 조회가 커지면 `idx_stock_history_stock_id_created_at (stock_id, created_at)` 추가를 검토한다.

### `tbl_order`

COLUMNS:
- `id (PK)`
- `version`
- `member_id (FK -> tbl_member.id)`
- `total_price`
- `status`
- `merchant_pay_key (UNIQUE)`
- `idempotency_key (NULL 허용)`

INDEX:
- `merchant_pay_key (UNIQUE)`
- `uk_order_member_idempotency (member_id, idempotency_key) UNIQUE`

비고:
- `idempotency_key`는 기존 데이터 및 멱등성 없는 경로와의 호환을 위해 NULL 허용. MySQL에서 NULL 값은 unique 제약 대상에서 제외된다.

### `tbl_order_item`

COLUMNS:
- `id (PK)`
- `order_id (FK -> tbl_order.id)`
- `product_id (FK -> tbl_product.id)`
- `quantity`

INDEX:
- 없음

### `tbl_cart_item`

COLUMNS:
- `id (PK)`
- `member_id`
- `product_id`
- `quantity`
- `version` (BIGINT NOT NULL DEFAULT 0, `@Version` 낙관적 락)
- `created_at`
- `updated_at`

INDEX:
- `uk_cart_item_member_product (member_id, product_id) UNIQUE`

비고:
- `member_id`, `product_id`는 FK 제약을 두지 않는다. cart 도메인은 다른 aggregate를 `Long` ID로만 참조한다(ADR-020).
- `(member_id, product_id)` UNIQUE 복합 인덱스가 같은 회원의 같은 상품 중복 row를 차단하고, `findAllByMemberIdOrderByCreatedAtDesc`·`findByMemberIdAndProductId`·`deleteByMemberIdAndProductIdIn` 조회 인덱스도 함께 제공한다. 별도의 단독 `member_id` 인덱스는 두지 않는다(복합 인덱스 prefix가 동일 커버).
- `version` 컬럼은 cart phase ADR 결정 8(낙관적 락 + retry + Processor 분리)을 따른다. JPA `@Version`이 UPDATE 시점에 version 비교로 update race를 감지하고, 응용 Service의 retry loop가 `ObjectOptimisticLockingFailureException`을 흡수한다.
- 신규 항목 동시 insert race window의 UNIQUE 충돌은 ADR-011 find-first 패턴 + 안전망 500으로 위임한다. retry catch에는 포함하지 않는다.
- `quantity`는 도메인 invariant(`MIN=1, MAX=99`)와 DTO Bean Validation(`@Min(1) @Max(99)`)이 이중 가드한다.

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

비고:
- unique key 대상 4개 컬럼(`merchant_pay_key`, `provider`, `payment_id`, `type`)은 `@Column(length=...)`을 명시한다 (각각 64/32/64/32). utf8mb4 + InnoDB unique key 한도 3072 bytes 안에 들어오도록 산정. 상세는 ADR-023 및 `docs/tasks/payment-attempt-unique-key-length/adr.md` 참조.

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
- `trace_id (VARCHAR(64) NULL)`

INDEX:
- `event_id (UNIQUE)`
- `idx_outbox_event_type_status_next_retry_id (event_type, status, next_retry_at, id)`

비고:
- `trace_id`는 outbox 생성 시점의 MDC traceId. relay 시 MDC로 복원되어 `TraceIdKafkaProducerInterceptor`가 Kafka 헤더 `X-Trace-Id`로 자동 전파한다. 형식은 `LogContext.isValidTraceId()`의 `^[A-Za-z0-9_-]{1,64}$`와 일치하며, 유효한 traceId가 없으면 `NULL`로 저장한다.

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
- `tbl_stock` 1:N `tbl_stock_history`
- `tbl_product` 1:N `tbl_order_item`
- `tbl_order` 1:1 `tbl_payment`
