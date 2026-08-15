# DB 스키마

이 문서는 테이블·컬럼·제약의 의도를 설명하는 reference다. 실제 DDL의 단일 출처는 Flyway 마이그레이션 스크립트(`src/main/resources/db/migration/V*__*.sql`)다. 이 문서는 스키마를 복제하지 않고, "이 테이블·컬럼이 무엇인지"와 관련 결정을 가리킨다.

마이그레이션 관리 규칙(Flyway, `ddl-auto: validate`, enum·unique 컬럼 매핑 등)은 `docs/persistence-conventions.md`를 따른다.

## 마이그레이션 이력

주요 스키마 변경 이력만 남긴다. 마이그레이션 운영 규칙 자체는 `docs/persistence-conventions.md`에 있다.

- **cross-aggregate ID 참조 결정(→ PR#166)의 후속 트랙 FK 정비**: `V4__drop_cross_aggregate_fk_constraints.sql` 으로 cross-aggregate FK 5건을 일괄 제거했다 (2026-06-03). UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 과 same-aggregate FK (`fk_order_item_order_id`) 는 유지한다. 세부 결정은 `docs/tasks/cross-aggregate-fk-cleanup/adr.md` 참조.
- **결제 시점 가격 snapshot**: `V5__add_order_item_unit_price.sql` 으로 `tbl_order_item.unit_price INT NOT NULL` 컬럼을 신설했다 (2026-06-03). 기존 row 는 `tbl_product.price` JOIN backfill 후 NOT NULL 전환. 세부 결정은 `docs/tasks/order-item-price-snapshot/adr.md` 참조.
- **결제 도메인 재설계**: `V6__redesign_payment_to_reservation_and_attempt.sql` 으로 (1) 기존 `tbl_payment` (성공 1:1) DROP, (2) `tbl_payment_attempt` → `tbl_payment` RENAME + 컬럼 정리 (order_id 신규, approved_order_key 신규, pg_payment_id NOT NULL 복원), (3) `tbl_payment_reservation` CREATE, (4) `tbl_order.merchant_pay_key` + `uk_order_merchant_pay_key` DROP. 세부 결정은 `docs/tasks/payment-order-redesign/db-schema.md` 참조 (→ PR#205).
- **결제·환불 모델 재구성**: 결제 예약 테이블을 없애고 결제 행이 그 역할을 흡수했으며, 환불이 독립 테이블로 갈라지고 결제사 호출 기록 테이블이 생겼다 (2026-08-16, → PR#305). 스크립트를 셋으로 나눈 것은 옛 모델과 새 모델이 한동안 나란히 살아야 하는데 둘 다 `tbl_payment` 이름에 매핑되면 시작 시 매핑 검증에서 실패하기 때문이다.
  - `V11__rename_legacy_payment_tables.sql` — `tbl_payment` → `tbl_legacy_payment`, `tbl_payment_reservation` → `tbl_legacy_payment_reservation` RENAME. 데이터는 그대로 남아 legacy 경로가 계속 동작한다.
  - `V12__create_payment_and_refund.sql` — `tbl_payment`(재구성) · `tbl_refund` · `tbl_pg_call_log` CREATE.
  - `V13__drop_legacy_payment_tables.sql` — legacy 두 테이블 DROP. **파괴적 마이그레이션**이며 옛 결제·예약 데이터는 이관하지 않고 폐기한다 (운영 데이터 없음 전제).

## 네이밍 규칙

- 테이블명: `tbl_<domain>`
- 일반 인덱스: `idx_<target>_<columns>`
- 유니크 키/유니크 인덱스: `uk_<target>_<columns>`
- 외래 키: `fk_<source_table>_<source_columns>`

예시:
- `tbl_member`
- `tbl_refund`
- `idx_outbox_event_type_status_next_retry_id`
- `uk_payment_active_order_key`

## 작성 형식

각 테이블은 아래 형식으로 기술한다. 컬럼의 "왜"(설계 결정)는 이 문서에 길게 적지 않고 관련 ADR을 가리킨다(→ PR#\<번호\>).

```
### `tbl_<domain>`

COLUMNS:
- `id (PK)`
- `<column> (<제약>)`

INDEX:
- `<index>`

비고:
- <테이블/컬럼의 의도 한 줄> (→ PR#<번호>)
```

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
- `product_id` 는 FK 제약을 두지 않는다. `fk_stock_product_id` 가 V4 migration 으로 제거됐다 (→ PR#166 후속 트랙). `uk_stock_product_id` UNIQUE 제약은 Stock 1:1 Product 도메인 invariant 로 유지된다.

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
- `stock_id` 는 FK 제약을 두지 않는다. `fk_stock_history_stock_id` 가 V4 migration 으로 제거됐다 (→ PR#166 후속 트랙). 동명 KEY index (`KEY fk_stock_history_stock_id (stock_id)`) 는 조회 보조용으로 유지된다.
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
- `member_id` 는 FK 제약을 두지 않는다. `fk_order_member_id` 가 V4 migration 으로 제거됐다 (→ PR#166 후속 트랙).
- `idempotency_key`는 기존 데이터 및 멱등성 없는 경로와의 호환을 위해 NULL 허용. MySQL에서 NULL 값은 unique 제약 대상에서 제외된다.
- `merchant_pay_key` 컬럼과 `uk_order_merchant_pay_key` 는 V6 migration 으로 제거됐다 (→ PR#205). Order 는 결제 식별자를 모른다 — 결제 식별자는 `tbl_payment` 가 갖는다.

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
- `product_id` 는 FK 제약을 두지 않는다. `fk_order_item_product_id` 가 V4 migration 으로 제거됐다 (→ PR#166 후속 트랙). 동명 KEY index (`KEY fk_order_item_product_id (product_id)`) 는 조회 보조용으로 유지된다.
- `order_id (FK -> tbl_order.id)` 는 same-aggregate FK 로 유지된다. cross-aggregate ID 참조 결정(→ PR#166)의 적용 범위 밖 (Order ↔ OrderItem 은 같은 aggregate).
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
- `member_id`, `product_id`는 FK 제약을 두지 않는다. cart 도메인은 다른 aggregate를 `Long` ID로만 참조한다.
- `(member_id, product_id)` UNIQUE 복합 인덱스가 같은 회원의 같은 상품 중복 row를 차단하고, `findAllByMemberIdOrderByCreatedAtDesc`·`findByMemberIdAndProductId`·`deleteByMemberIdAndProductIdIn` 조회 인덱스도 함께 제공한다. 별도의 단독 `member_id` 인덱스는 두지 않는다(복합 인덱스 prefix가 동일 커버).
- `version` 컬럼은 cart phase ADR 결정 8(낙관적 락 + retry + Processor 분리)을 따른다. JPA `@Version`이 UPDATE 시점에 version 비교로 update race를 감지하고, 응용 Service의 retry loop가 `ObjectOptimisticLockingFailureException`을 흡수한다.
- 신규 항목 동시 insert race window의 UNIQUE 충돌은 find-first 패턴(→ PR#109) + 안전망 500으로 위임한다. retry catch에는 포함하지 않는다.
- `quantity`는 도메인 invariant(`MIN=1, MAX=99`)와 DTO Bean Validation(`@Min(1) @Max(99)`)이 이중 가드한다.

### `tbl_payment` (V12 재구성)

결제 시도 원장. 결제창을 띄우기 전에 행이 먼저 생기고, 그 행이 승인 결과까지 담는다. 옛 예약 테이블(`tbl_payment_reservation`)이 하던 일 — 결제창 준비물 보관과 신원 확인 — 을 이 테이블이 흡수했다 (→ PR#305).

COLUMNS:
- `id (PK)`
- `order_id BIGINT NOT NULL` — 소속 Order PK 값. FK 제약 없음 (참조용 값)
- `member_id BIGINT NOT NULL` — 소유 회원. 승인 요청의 신원 확인용
- `payment_key VARCHAR(64) NOT NULL` — 우리가 발급해 결제사에 보내는 결제 키. 시도마다 새로 발급한다
- `idempotency_key VARCHAR(64) NOT NULL` — 결제 시작 멱등키. 밖에서 받은 값
- `pg_payment_id VARCHAR(64) NULL` — 결제사 결제 번호. 승인 인증 뒤에 채워지므로 NULL 허용
- `pg_transaction_id VARCHAR(64) NULL` — 결제사 거래 번호. 정산 대조·문의 조사용이며 판정에는 쓰지 않는다
- `amount INT NOT NULL` — 결제 시작 시점 주문 금액의 사본
- `approved_amount INT NULL` — 결제사가 승인한 금액. 환불 한도 계산의 기준
- `total_refunded_amount INT NOT NULL DEFAULT 0` — 이 결제에 딸린 환불 금액의 합. 한도 판정이 읽는 유일한 값
- `pg VARCHAR NOT NULL` — enum. 어느 결제사인가
- `status VARCHAR NOT NULL` — enum. `READY` / `IN_PROGRESS` / `UNKNOWN` / `SUCCEEDED` / `FAILED` / `REJECTED` / `EXPIRED`
- `active_order_key BIGINT NULL` — 활성 슬롯. 살아 있으면 `order_id`, 종결되면 NULL (NULL 트릭)
- `close_code VARCHAR NULL` — enum. `FAILED`·`REJECTED`·`EXPIRED` 일 때만 채우는 종결 코드
- `close_detail VARCHAR(255) NULL` — 결제사가 준 문구. 조사용이며 분기에 쓰지 않는다
- `last_requested_at DATETIME(6) NULL` — 결제사를 부른 시각. 승인을 부를 때마다 갱신
- `last_reconcile_at DATETIME(6) NULL` — 마지막으로 대사가 이 건을 집은 시각. 확정 여부와 무관하게 남긴다
- `last_notify_at DATETIME(6) NULL` — 마지막으로 운영자에게 알린 시각. 통지를 보낸 뒤에 찍는다
- `attempt_seq INT NOT NULL DEFAULT 0` — 시도 번호. 승인 호출 멱등키에 붙는다. 부른 횟수가 아니다 (결과를 모를 때는 같은 키로 다시 부르므로 오르지 않는다)
- `reconcile_count INT NOT NULL DEFAULT 0` — 대사가 이 건을 몇 번 집었나. 다시 집는 간격을 고르는 값
- `created_at DATETIME(6) NOT NULL`
- `updated_at DATETIME(6) NOT NULL`
- `version BIGINT NOT NULL` — `@Version` 낙관적 락

INDEX:
- `uk_payment_payment_key (payment_key) UNIQUE` — 결제 키 유일 보장이자 승인 조회 경로
- `uk_payment_member_idempotency (member_id, idempotency_key) UNIQUE` — 같은 결제 시작 요청이 두 번 오는 것을 차단. 유일 범위가 회원인 것은 주문 생성과 같다
- `uk_payment_active_order_key (active_order_key) UNIQUE` — **한 주문에 활성 결제 하나**. 같은 주문에 결제가 동시에 둘 살아 있는 것을 막아 이중결제를 차단한다 (NULL 트릭)
- `idx_payment_order (order_id)` — 주문별 시도 조회
- `idx_payment_status_reconcile (status, reconcile_count, last_reconcile_at)` — 대사·통지 대상 조회. 선두 `status` 로 크게 좁히고, 회차별로 나뉘는 `reconcile_count` 가 둘째, 임계 시각이 셋째로 범위를 좁힌다

비고:
- **NULL 트릭 캡슐화**: `active_order_key` 값 set/해제는 상태 전이와 *같은 UPDATE* 안에서 도메인 메서드를 통해서만 일어난다. 따로 두면 상태와 슬롯이 어긋난다. 직접 UPDATE 금지
- **어느 경로로 종결되든 슬롯을 반납한다.** `FAILED`·`REJECTED`·`EXPIRED` 가 되면 NULL 로 비운다. 안 그러면 그 주문을 영영 다시 결제할 수 없다. 다만 새 요청이 남의 결제를 강제 종결시켜 슬롯을 가져가는 것은 `READY` 행에만 허용된다 (→ PR#305)
- **대사가 쓰는 시각은 셋 다 "마지막으로 무엇을 한 시각"** 이고, NULL 은 "아직 안 했다"는 뜻이다. 다음에 읽을 시각을 미리 계산해 넣지 않는다 — 간격 정책을 바꿔도 이미 쌓인 행에 반영되게 하려는 것이다. `created_at`·`updated_at` 으로는 대사 유예를 잴 수 없다 (→ PR#305)
- **`pg_payment_id` 에 유일 제약도 인덱스도 두지 않는다.** 그 값으로 결제 행을 찾는 조회를 만들지 않고, 클라이언트가 보내는 값을 검증 전에 채우므로 제약을 걸면 아무 번호나 실어 정당한 주인의 승인을 막을 수 있다 (→ PR#305)
- **FK**: `order_id`, `member_id` 는 FK 제약 없음 (참조용 값)
- unique key 대상 문자열 컬럼(`payment_key` 64, `idempotency_key` 64)은 `@Column(length=...)` 을 명시한다. utf8mb4 + InnoDB unique key 한도 3072 bytes 안에 들어오도록 산정

### `tbl_refund` (신설 — V12)

환불 사건. 결제와 각자 독립 aggregate 라 한 결제에 환불이 여러 건 딸릴 수 있다 (→ PR#305).

COLUMNS:
- `id (PK)`
- `payment_id BIGINT NOT NULL` — 소속 Payment PK 값. FK 제약 없음 (경계를 넘는 참조)
- `refund_key VARCHAR(40) NOT NULL` — 환불 사건 키. 시도 번호를 붙여 결제사에 거래 키로 보내고, 이력에서는 이 값을 접두어로 우리 환불을 찾는다. 파생값이 결제사 한도(64자)를 넘지 못하게 컬럼을 40자로 좁혔다
- `idempotency_key VARCHAR(64) NOT NULL` — 환불 요청 멱등키. 회원 환불은 밖에서 받은 값을, 시스템이 만드는 환불은 `reason` 값을 담는다 (비우면 유일 검사에서 빠져 DB가 중복을 못 막는다)
- `requester VARCHAR(20) NOT NULL` — enum. `MEMBER` / `SYSTEM`. 유일 제약에 들어가는 enum 이라 예외적으로 길이를 명시한다
- `amount INT NOT NULL` — 이번 환불 금액
- `reason VARCHAR NOT NULL` — enum. `ORDER_CANCELED` / `ORDER_NOT_PAYABLE` / `AMOUNT_MISMATCH`
- `pg_idempotency_key VARCHAR(64) NOT NULL` — 결제사 호출 멱등키. 사건 키에 시도 번호를 붙여 파생한다
- `status VARCHAR NOT NULL` — enum. `REQUESTED` / `IN_PROGRESS` / `UNKNOWN` / `SUCCEEDED` / `MANUAL_REVIEW`
- `pg_transaction_id VARCHAR(64) NULL` — 결제사 거래 번호. 정산 대조·문의 조사용
- `review_code VARCHAR NULL` — enum. 왜 자동으로 더 진행하지 못하게 됐나. 채워졌다는 것이 곧 `MANUAL_REVIEW` 다
- `review_detail VARCHAR(255) NULL`
- `last_requested_at DATETIME(6) NULL` — 결제사를 부른 시각. **재전송마다 갱신된다**
- `last_reconcile_at DATETIME(6) NULL` — 마지막으로 대사가 집은 시각
- `last_notify_at DATETIME(6) NULL` — 마지막으로 운영자에게 알린 시각
- `attempt_seq INT NOT NULL DEFAULT 0` — 시도 번호. 0 이면 한 번도 부르지 않은 건
- `reconcile_count INT NOT NULL DEFAULT 0` — 대사가 이 건을 몇 번 집었나
- `created_at DATETIME(6) NOT NULL`
- `updated_at DATETIME(6) NOT NULL`
- `version BIGINT NOT NULL` — `@Version` 낙관적 락

INDEX:
- `uk_refund_refund_key (refund_key) UNIQUE` — 환불 사건 키 유일 보장
- `uk_refund_payment_idempotency (payment_id, requester, idempotency_key) UNIQUE` — 같은 환불 요청이 두 번 오는 것을 차단. 유일 범위를 그 결제에 딸린 환불로 두고, `requester` 가 들어가 회원 값과 시스템 값이 다른 공간에 놓인다
- `uk_refund_pg_idempotency_key (pg_idempotency_key) UNIQUE` — 두 환불이 같은 결제사 호출 멱등키를 갖는 것을 차단. 겹치는 것은 시도 번호를 안 올린 코드 버그일 때뿐이라, 그 버그를 드러내려고 둔다
- `idx_refund_payment (payment_id)` — 결제별 환불 조회
- `idx_refund_status_reconcile (status, reconcile_count, last_reconcile_at)` — 발송·회수·통지 대상 조회. `tbl_payment` 와 같은 구성

비고:
- **환불도 자기 낙관 락을 갖는다.** 환불을 *만드는* 것은 결제 행의 버전이 막고(그때만 한도가 바뀐다), 환불 하나를 *고치는* 것은 이 버전이 막는다. 그래서 대사가 한 바퀴 돌아도 결제 버전이 오르지 않아 회원의 환불 요청이 밀리지 않는다 (→ PR#305)
- **FK**: `payment_id` 는 FK 제약 없음. 환불이 독립 aggregate 가 되면서 이 참조가 경계를 넘게 됐고, 이 저장소는 경계를 넘는 외래 키를 두지 않는다
- 유일 제약이 셋인 것은 세 값의 책임이 달라서다 — 사건을 가리키고, 같은 요청이 두 번 오는 것을 막고, 한 번의 호출이 중복 전송되는 것을 결제사가 막게 한다
- **`EXPIRED` 가 없다** — 환불은 포기할 수 없어 "안 부른 채 끝남"이라는 종착이 없다
- 결제사를 거치지 않는 환불(카드 해지·취소 기한 만료 등 사람이 처리하는 경로)과 처리한 관리자 식별자는 이번 범위 밖이라 컬럼을 두지 않았다

### `tbl_pg_call_log` (신설 — V12)

결제사를 부른 사실을 쌓는다. 승인 호출과 환불 호출을 한 곳에 담고, 이력 조회는 담지 않는다. 판정에 쓰지 않고 문의·조사 때 근거로 본다 (→ PR#305).

COLUMNS:
- `id (PK)`
- `payment_id BIGINT NOT NULL` — 환불 호출도 결제에 딸리므로 항상 채운다
- `refund_id BIGINT NULL` — 환불 호출일 때만 채운다
- `call_type VARCHAR NOT NULL` — enum. `APPROVE` / `REFUND`
- `pg_idempotency_key VARCHAR(64) NOT NULL` — 그때 결제사에 실제로 보낸 멱등키. 결과를 모르는 동안은 같은 키로 다시 부르므로 같은 값이 여러 행에 반복될 수 있다
- `requested_at DATETIME(6) NOT NULL` — 부른 시각. 행을 만들 때 채우고 이후 바뀌지 않는다
- `responded_at DATETIME(6) NULL` — 응답을 받은 시각
- `error_type VARCHAR NULL` — enum. `CONNECT` / `TIMEOUT` / `HTTP` / `PARSE` / `NONE`. 응답 원본으로 역산할 수 없어 따로 담는다
- `result_code VARCHAR(64) NULL` — 결제사가 준 결과 코드. 검색·집계용
- `http_status INT NULL`
- `raw_response TEXT NULL` — 응답 원본 그대로
- `created_at DATETIME(6) NOT NULL`
- `updated_at DATETIME(6) NOT NULL`

INDEX:
- `idx_pg_call_log_payment_requested (payment_id, requested_at)` — 한 결제의 경위를 시간순으로
- `idx_pg_call_log_refund (refund_id)` — 환불 하나의 시도 이력
- `idx_pg_call_log_type_requested (call_type, requested_at)` — 시간대별 결제사 상태 조사

비고:
- **호출 직전에 행을 만들고(`requested_at` 만 채움), 결과를 받으면 그 행을 한 번 채운다.** 응답을 받은 뒤에 만들면 타임아웃일 때 행이 아예 안 생겨 요청이 갔는지를 영영 알 수 없다 — 그때가 바로 조사가 필요한 경우다. 재시도는 새 행이다
- **`version` 을 두지 않는다.** 한 행이 한 호출이고 결과를 채우는 UPDATE 가 한 번뿐이라 두 주체가 같은 행을 고칠 일이 없다
- **결제 트랜잭션과 섞이지 않는다.** 별도 트랜잭션으로 커밋해야 결제 쪽이 롤백돼도 "불렀다"는 사실이 남는다
- `responded_at` 이 비어 있어도 그것만으로 결과 불명인 건을 세지 않는다. 결과 반영이 이 행의 갱신보다 앞서므로 이미 확정된 건에도 빈 행이 남을 수 있다 — 결과 불명인지는 결제·환불 행의 상태가 말한다
- **FK**: `payment_id`, `refund_id` 는 FK 제약 없음 (참조용 값)

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
- `tbl_order` 1:N `tbl_payment` (orderId 값 참조, FK 제약 없음). 살아 있는 결제는 활성 슬롯 UNIQUE 로 최대 하나
- `tbl_payment` 1:N `tbl_refund` (paymentId 값 참조, FK 제약 없음)
- `tbl_payment` 1:N `tbl_pg_call_log`, `tbl_refund` 1:N `tbl_pg_call_log` (값 참조, FK 제약 없음)

> **V12 이전 관계**: `tbl_order` 1:N `tbl_payment_reservation`, `tbl_payment_reservation` 1:N `tbl_payment` 는 결제·환불 모델 재구성(→ PR#305)으로 폐기됐다. 예약 테이블이 사라지고 그 역할을 결제 행이 흡수했다.
