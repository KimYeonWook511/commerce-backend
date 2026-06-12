# DB 스키마

## 마이그레이션

DB 스키마 변경은 Flyway 마이그레이션 스크립트로 관리한다 (ADR-024).

- 위치: `src/main/resources/db/migration/`
- 네이밍: `V{번호}__{snake_case_설명}.sql`
- 본 문서는 테이블/컬럼/제약의 의도를 설명하는 reference이고, 실제 DDL은 `V*__*.sql`이 단일 출처다.
- 엔티티(@Entity) 변경 PR은 같은 PR에서 대응되는 V 스크립트를 함께 작성한다. ddl-auto: validate라 누락 시 부팅 실패.
- 적용된 V 스크립트는 수정하지 말고 새 V로 보정한다 (Flyway checksum).
- **ADR-020 후속 트랙 FK 정비**: `V4__drop_cross_aggregate_fk_constraints.sql` 으로 cross-aggregate FK 5건을 일괄 제거했다 (2026-06-03). UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 과 same-aggregate FK (`fk_order_item_order_id`) 는 유지한다. 세부 결정은 `docs/tasks/cross-aggregate-fk-cleanup/adr.md` 참조.
- **결제 시점 가격 snapshot**: `V5__add_order_item_unit_price.sql` 으로 `tbl_order_item.unit_price INT NOT NULL` 컬럼을 신설했다 (2026-06-03). 기존 row 는 `tbl_product.price` JOIN backfill 후 NOT NULL 전환. 세부 결정은 `docs/tasks/order-item-price-snapshot/adr.md` 참조.
- **결제 도메인 재설계**: `V6__redesign_payment_to_reservation_and_attempt.sql` 으로 (1) 기존 `tbl_payment` (성공 1:1) DROP, (2) `tbl_payment_attempt` → `tbl_payment` RENAME + 컬럼 정리 (order_id 신규, approved_order_key 신규, pg_payment_id NOT NULL 복원), (3) `tbl_payment_reservation` CREATE, (4) `tbl_order.merchant_pay_key` + `uk_order_merchant_pay_key` DROP. 세부 결정은 `docs/tasks/payment-order-redesign/db-schema.md` 참조 (ADR-026).

## 네이밍 규칙

- 테이블명: `tbl_<domain>`
- 일반 인덱스: `idx_<target>_<columns>`
- 유니크 키/유니크 인덱스: `uk_<target>_<columns>`
- 외래 키: `fk_<source_table>_<source_columns>`

예시:
- `tbl_member`
- `tbl_payment_reservation`
- `idx_outbox_event_type_status_next_retry_id`
- `uk_payment_reservation_reserved_key`

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
- `product_id (UNIQUE)`
- `quantity`

INDEX:
- `product_id (UNIQUE)`

비고:
- `product_id` 는 FK 제약을 두지 않는다. `fk_stock_product_id` 가 V4 migration 으로 제거됐다 (ADR-020 후속 트랙). `uk_stock_product_id` UNIQUE 제약은 Stock 1:1 Product 도메인 invariant 로 유지된다.

### `tbl_stock_history`

COLUMNS:
- `id (PK)`
- `stock_id`
- `quantity_change`
- `reason`
- `admin_member_id`
- `created_at`
- `updated_at`

INDEX:
- 없음

비고:
- `stock_id` 는 FK 제약을 두지 않는다. `fk_stock_history_stock_id` 가 V4 migration 으로 제거됐다 (ADR-020 후속 트랙). 동명 KEY index (`KEY fk_stock_history_stock_id (stock_id)`) 는 조회 보조용으로 유지된다.
- 상품별 재고 이력 최신순 조회가 커지면 `idx_stock_history_stock_id_created_at (stock_id, created_at)` 추가를 검토한다.

### `tbl_order`

COLUMNS:
- `id (PK)`
- `version`
- `member_id`
- `total_price`
- `status`
- `idempotency_key (NULL 허용)`

INDEX:
- `uk_order_member_idempotency (member_id, idempotency_key) UNIQUE`

비고:
- `member_id` 는 FK 제약을 두지 않는다. `fk_order_member_id` 가 V4 migration 으로 제거됐다 (ADR-020 후속 트랙).
- `idempotency_key`는 기존 데이터 및 멱등성 없는 경로와의 호환을 위해 NULL 허용. MySQL에서 NULL 값은 unique 제약 대상에서 제외된다.
- `merchant_pay_key` 컬럼과 `uk_order_merchant_pay_key` 는 V6 migration 으로 제거됐다 (ADR-026). merchantPayKey 책임은 `tbl_payment_reservation` 으로 이동했다. Order 는 결제 식별자를 모른다.

### `tbl_order_item`

COLUMNS:
- `id (PK)`
- `order_id (FK -> tbl_order.id)`
- `product_id`
- `quantity`
- `unit_price INT NOT NULL`

INDEX:
- 없음

비고:
- `product_id` 는 FK 제약을 두지 않는다. `fk_order_item_product_id` 가 V4 migration 으로 제거됐다 (ADR-020 후속 트랙). 동명 KEY index (`KEY fk_order_item_product_id (product_id)`) 는 조회 보조용으로 유지된다.
- `order_id (FK -> tbl_order.id)` 는 same-aggregate FK 로 유지된다. ADR-020 적용 범위 밖 (Order ↔ OrderItem 은 같은 aggregate).
- `unit_price` 는 V5 migration 으로 신설된 결제 시점 가격 snapshot 컬럼이다. Product.price 변동 후에도 결제 시점 단가가 보존된다. 세부 결정은 `docs/tasks/order-item-price-snapshot/adr.md` 참조.

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

### `tbl_payment_reservation` (신규 — V6)

결제창 준비물. 서버가 reserve 단계에서 merchantPayKey 를 발급해 저장한다. redirect 역조회 entry point 역할. `RESERVED → USED` 한 번 전이만 허용.

COLUMNS:
- `id (PK)`
- `order_id BIGINT NOT NULL` — 소속 Order PK 값. FK 제약 없음 (참조용 값)
- `member_id BIGINT NOT NULL` — 소유 회원. approve 진입 시 검증용
- `provider VARCHAR(32) NOT NULL` — PG (`NAVERPAY` 등)
- `merchant_pay_key VARCHAR(64) NOT NULL` — 서버 발급. redirect 역조회 키
- `amount INT NOT NULL` — 결제 예정 금액 (승인 시 PG 응답 대조용)
- `status VARCHAR(32) NOT NULL` — `RESERVED` / `USED` / `EXPIRED`
- `expires_at DATETIME(6) NOT NULL` — 만료 시각 (재사용/박제 판단용)
- `reserved_key VARCHAR(96) NULL` — RESERVED 일 때만 `"{order_id}:{provider}"`, USED/EXPIRED 면 NULL (NULL 트릭)
- `version BIGINT NOT NULL DEFAULT 0` — `@Version` 낙관적 락 (V7). 같은 예약의 동시 이중 use 차단용
- `created_at DATETIME(6) NOT NULL`
- `updated_at DATETIME(6) NOT NULL`

INDEX:
- `uk_payment_reservation_merchant_pay_key (merchant_pay_key) UNIQUE` — redirect 역조회 키 unique 보장
- `uk_payment_reservation_reserved_key (reserved_key) UNIQUE` — RESERVED 중 (주문, 수단) 1 개 보장. reserve 중복 요청(따닥) 차단 (NULL 트릭)
- `idx_reservation_order (order_id)` — UNKNOWN 차단 검사 / 주문별 조회

비고:
- **NULL 트릭 캡슐화**: `reserved_key` 값 set 은 *반드시* `status=RESERVED` 와 같은 INSERT 안에서. status 가 USED/EXPIRED 로 가면 *같은 UPDATE* 에서 NULL 로 비움. 도메인 계층을 통해서만 변경하며 직접 UPDATE 금지
- **상태 전이**: `RESERVED → USED` (승인 시작) 또는 `RESERVED → EXPIRED` (만료/무효 회수) 한 번 전이만 허용. 만료/무효 예약은 reserve 진입 시 `reserved_key` 를 NULL 로 회수해 재예약 허용 (박제 자동 복구)
- **동시 이중 use 차단**: `version` `@Version` 낙관적 락이 같은 예약을 다른 pgPaymentId 로 동시에 `USED` 전이하려는 경합에서 진 쪽을 차단한다. 승인 기록 전용 저장 경로(`saveUsed`)가 `saveAndFlush` 조기 flush 로 충돌을 PG 호출 전에 확정해 `PAYMENT_RESERVATION_ALREADY_USED` 도메인 예외로 번역한다. cart 의 retry 흡수와 달리 진 쪽은 재시도 없이 차단된다 (다른 pgPaymentId 는 별개 결제이므로). 세부 결정은 ADR-036
- **amount 불변**: 결제 예정 금액이 바뀌면 새 Reservation 발급. 기존 행 amount UPDATE 금지
- **FK**: `order_id`, `member_id` 는 FK 제약 없음 (참조용 값)

### `tbl_payment` (V6 이후 — 구 `tbl_payment_attempt` rename)

> 기존 `tbl_payment` (성공 결제 1:1 단위) 는 V6 migration 으로 DROP 됐다. 이름을 차지하는 것은 구 `tbl_payment_attempt` 이며 의미는 *PG 에 보낸 실제 요청 사건* (append-only).

PG 에 실제로 보낸 요청 사건. type ∈ `{APPROVE, CANCEL}`. append-only.

COLUMNS:
- `id (PK)`
- `version BIGINT NOT NULL DEFAULT 0` — `@Version` 낙관적 락 (V9). 같은 행 동시 종착 전이 lost update 차단 (ADR-050)
- `order_id BIGINT NOT NULL` — 소속 Order PK 값. FK 제약 없음 (참조용 값)
- `merchant_pay_key VARCHAR(64) NOT NULL` — 어느 Reservation 에서 비롯됐는지 (값으로 연결)
- `pg_payment_id VARCHAR(64) NOT NULL` — PG 가 발급한 외부 결제 ID. NOT NULL (RESERVE 가 빠져 항상 존재)
- `amount INT NOT NULL` — "그 시도가 움직인 금액"
- `provider VARCHAR(32) NOT NULL`
- `type VARCHAR(32) NOT NULL` — `APPROVE` / `CANCEL`
- `status VARCHAR(32) NOT NULL` — `REQUESTED` / `SUCCEEDED` / `FAILED` / `UNKNOWN`
- `fail_code VARCHAR(32) NULL`
- `fail_detail VARCHAR(255) NULL`
- `approved_order_key BIGINT NULL` — APPROVE+SUCCEEDED 일 때만 `order_id`, 그 외 NULL (NULL 트릭)
- `responded_at DATETIME(6) NULL`
- `escalated_at DATETIME(6) NULL` — escalation(운영 위임) 시각. NULL 이면 미escalation. `status` 와 무관한 직교 필드 (V8, ADR-049)
- `created_at DATETIME(6) NOT NULL`
- `updated_at DATETIME(6) NOT NULL`

INDEX:
- `uk_payment_approved_order_key (approved_order_key) UNIQUE` — 주문당 성공 APPROVE 1 개 보장. 이중결제 최종 방어선 (NULL 트릭)
- `uk_payment_merchant_pay_key_provider_pg_payment_id_type (merchant_pay_key, provider, pg_payment_id, type) UNIQUE` — 같은 시도 중복 기록 차단
- `idx_payment_order (order_id)` — UNKNOWN 차단 검사 / 주문별 조회

비고:
- **NULL 트릭 캡슐화**: `approved_order_key` 값 set 은 *반드시* `status=SUCCEEDED AND type=APPROVE` 와 같은 UPDATE 안에서. 그 외 모든 상태/타입에선 NULL. 도메인 메서드 (`succeed`) 안에 캡슐화. 우회 setter 금지
- **FK**: `order_id` 는 FK 제약 없음 (참조용 값)
- **append-only**: Payment 행은 한번 INSERT 후 상태 전이 (UPDATE) 만 일어남. 행 삭제 금지
- unique key 대상 컬럼(`merchant_pay_key` 64, `provider` 32, `pg_payment_id` 64, `type` 32)은 `@Column(length=...)`을 명시한다. utf8mb4 + InnoDB unique key 한도 3072 bytes 안에 들어오도록 산정 (ADR-023 참조)
- **escalation 멱등**: `escalated_at` set 은 도메인 메서드 `Payment.escalate(now)`(가드 `escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED)`) + `@Version`(`version` 컬럼) 낙관 락으로 수행. transition 의 `saveChecked` 성공 1 건만 escalation 통지 주체가 되고 동시 race 의 진 쪽은 `PAYMENT_CONCURRENTLY_MODIFIED` 로 skip 된다 (동시 race 에서도 1회 보장). 조건부 UPDATE 영향 행 수 방식에서 환원 — `@Version` 도입(V9, ADR-050) 으로 전제 해소 (ADR-049 → ADR-052)

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
- `tbl_order` 1:N `tbl_payment_reservation` (orderId 값 참조, FK 제약 없음)
- `tbl_payment_reservation` 1:N `tbl_payment` (merchantPayKey 값 참조, FK 제약 없음)
- `tbl_order` 1:N `tbl_payment` (orderId 값 참조, FK 제약 없음)

> **V6 이전 관계**: `tbl_order` 1:1 `tbl_payment` (성공 결제 1:1 모델) 는 ADR-026 결제 도메인 재설계로 폐기됐다.
