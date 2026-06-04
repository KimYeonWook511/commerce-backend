# 태스크 DB 스키마

## 개요

- 결제 도메인 재설계에 따른 schema 변경.
- **두 테이블 분리** — `tbl_payment_reservation` (신규) + `tbl_payment` (rename from `tbl_payment_attempt`, 컬럼 정리).
- 기존 `tbl_payment` (성공 결제 1:1) 폐기.
- `tbl_order` 에서 `merchant_pay_key` 관련 제거.
- 운영 데이터 없음 가정 — backfill 없이 단순 schema 변경.

## 신규 테이블

### `tbl_payment_reservation` (신규)

결제창 준비물. status `{RESERVED, USED, EXPIRED}` 의 한 번 전이만 허용 (RESERVED → USED 또는 RESERVED → EXPIRED).

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| `order_id` | BIGINT | NOT NULL | 소속 Order PK 값. FK 제약 없음 |
| `member_id` | BIGINT | NOT NULL | 소유 회원. approve 진입 시 검증용 |
| `provider` | VARCHAR(32) | NOT NULL | PG (`NAVERPAY` 등) |
| `merchant_pay_key` | VARCHAR(64) | NOT NULL | 서버 발급. redirect 역조회 키 |
| `amount` | INT | NOT NULL | 결제 예정 금액 (승인 시 PG 응답 대조용) |
| `status` | VARCHAR(32) | NOT NULL | `RESERVED` / `USED` / `EXPIRED` |
| `expires_at` | DATETIME(6) | NOT NULL | 만료 시각 (재사용/박제 판단) |
| `reserved_key` | VARCHAR(96) | NULL | RESERVED 일 때만 `"{order_id}:{provider}"`, USED/EXPIRED 면 NULL (NULL 트릭) |
| `created_at` | DATETIME(6) | NOT NULL | BaseTimeEntity |
| `updated_at` | DATETIME(6) | NOT NULL | BaseTimeEntity |

## 변경 테이블

### `tbl_payment` (신규 — `tbl_payment_attempt` rename + 컬럼 정리)

기존 `tbl_payment_attempt` 가 `tbl_payment` 이름을 차지. 의미는 *PG 에 보낸 실제 요청 사건* (append-only). 컬럼은 RESERVE 가 빠지므로 정리.

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| `order_id` | BIGINT | NOT NULL | **신규**. 소속 Order. FK 제약 없음 (참조용 값) |
| `merchant_pay_key` | VARCHAR(64) | NOT NULL | 기존 유지. 어느 Reservation 에서 비롯됐는지 (값으로 연결) |
| `pg_payment_id` | VARCHAR(64) | NOT NULL | **NOT NULL 복원** (RESERVE 가 빠지므로 항상 존재) |
| `amount` | INT | NOT NULL | 기존 유지. "그 시도가 움직인 금액" |
| `provider` | VARCHAR(32) | NOT NULL | 기존 유지 |
| `type` | VARCHAR(32) | NOT NULL | `APPROVE` / `CANCEL` (RESERVE 없음) |
| `status` | VARCHAR(32) | NOT NULL | `REQUESTED` / `SUCCEEDED` / `FAILED` / **`UNKNOWN`** (RESERVED/EXPIRED 없음) |
| `fail_code` | VARCHAR(32) | NULL | 기존 유지 |
| `fail_detail` | VARCHAR(255) | NULL | 기존 유지. length 제한 |
| `approved_order_key` | BIGINT | NULL | **신규**. APPROVE+SUCCEEDED 일 때만 `order_id`, 그 외 NULL (NULL 트릭) |
| `responded_at` | DATETIME(6) | NULL | 기존 유지 |
| `created_at` | DATETIME(6) | NOT NULL | BaseTimeEntity |
| `updated_at` | DATETIME(6) | NOT NULL | BaseTimeEntity |

### `tbl_order` (기존 테이블 컬럼 제거)

- DROP COLUMN `merchant_pay_key`
- DROP INDEX `uk_order_merchant_pay_key`

### `tbl_payment` (기존 — DROP)

기존 `tbl_payment` (성공 결제 1:1) 전체 DROP. rename 충돌이라 *기존 DROP → attempt rename* 순서.

## 인덱스

### `tbl_payment_reservation` 인덱스

| 인덱스 | 컬럼 | 목적 |
|---|---|---|
| PRIMARY | `id` | PK |
| `uk_payment_reservation_merchant_pay_key` UNIQUE | `merchant_pay_key` | redirect 역조회 키 unique 보장 |
| `uk_payment_reservation_reserved_key` UNIQUE | `reserved_key` | RESERVED 중 (주문, 수단) 1 개 보장 (NULL 트릭). reserve 따닥 차단 |
| `idx_reservation_order` | `order_id` | UNKNOWN 차단 검사 / 주문별 조회 |

### `tbl_payment` 인덱스

| 인덱스 | 컬럼 | 목적 |
|---|---|---|
| PRIMARY | `id` | PK |
| `uk_payment_approved_order_key` UNIQUE | `approved_order_key` | 주문당 성공 APPROVE 1 개 (NULL 트릭). 이중결제 최종 방어선 |
| `uk_payment_merchant_pay_key_provider_pg_payment_id_type` UNIQUE | `merchant_pay_key, provider, pg_payment_id, type` | 같은 시도 중복 기록 차단 |
| `idx_payment_order` | `order_id` | UNKNOWN 차단 검사 / 주문별 조회 |

(기존 `uk_payment_attempt_*` 는 새 이름으로 재생성)

## 데이터 무결성

### `tbl_payment_reservation` 무결성 규칙

- **NULL 트릭 캡슐화**:
  - `reserved_key` 값 set 은 *반드시* `status=RESERVED` 와 같은 INSERT 안에서. status 가 USED/EXPIRED 로 가면 *같은 UPDATE* 에서 NULL 로 비움 (`markUsed` / `markExpired`)
  - 도메인 메서드 (`createReserved`, `markUsed`) 안에 캡슐화. 우회 setter 금지
- **상태 전이**:
  - `RESERVED → USED` 한 번 전이만 허용. USED 행은 더 이상 변하지 않음
  - 만료/무효 예약은 reserve 진입 시 `markExpired` 로 회수 (status=EXPIRED + reservedKey=NULL). reservedKey 점유를 풀어 같은 (order, provider) 재예약을 허용 (ADR-5)
- **amount 변경 금지**: 결제 예정 금액이 바뀌면 새 Reservation 발급. 기존 행 amount UPDATE 금지
- **FK**: `order_id`, `member_id` 는 *참조용 값* 으로만 가짐. `FOREIGN KEY` 제약 없음

### `tbl_payment` 무결성 규칙

- **NULL 트릭 캡슐화**:
  - `approved_order_key` 값 set 은 *반드시* `status=SUCCEEDED` AND `type=APPROVE` 와 같은 UPDATE 안에서. 그 외 모든 상태/타입에선 NULL
  - 도메인 메서드 (`createRequested`, `succeed`, `fail`, `markUnknown`) 안에 캡슐화. 우회 setter 금지
- **FK**: `order_id` 는 *참조용 값* 으로만 가짐
- **append-only**: Payment 행은 한번 INSERT 후 *상태 전이 (UPDATE)* 만 일어남. 행 삭제 금지

### `tbl_order` 무결성

- merchant_pay_key 관련 제거. 나머지 제약 (`uk_order_member_idempotency`) 유지

## 마이그레이션 고려사항

### Flyway V6: `V6__redesign_payment_to_reservation_and_attempt.sql`

순서:

1. **DROP** `tbl_payment` (기존 성공 결제 1:1) — rename 충돌 회피
2. **RENAME** `tbl_payment_attempt` → `tbl_payment`
3. **ALTER** `tbl_payment`:
   - ADD COLUMN `order_id BIGINT NOT NULL`
   - ADD COLUMN `approved_order_key BIGINT NULL`
   - DROP INDEX old `uk_payment_attempt_merchant_pay_key_provider_pg_payment_id_type`
   - ADD INDEX new `uk_payment_merchant_pay_key_provider_pg_payment_id_type`
   - ADD UNIQUE `uk_payment_approved_order_key (approved_order_key)`
   - ADD INDEX `idx_payment_order (order_id)`
4. **CREATE TABLE** `tbl_payment_reservation` (위 스키마)
5. **ADD INDEX** `tbl_payment_reservation`:
   - UNIQUE `uk_payment_reservation_merchant_pay_key (merchant_pay_key)`
   - UNIQUE `uk_payment_reservation_reserved_key (reserved_key)`
   - `idx_reservation_order (order_id)`
6. **ALTER** `tbl_order`:
   - DROP INDEX `uk_order_merchant_pay_key`
   - DROP COLUMN `merchant_pay_key`

### 롤백

- 단일 트랜잭션 마이그레이션 권장 (DDL 이지만 MySQL 8 의 atomic DDL 지원)
- 운영 데이터 없음 가정이라 backfill / rollback SQL 미작성

### 배포 순서

- 학습/포트폴리오 단계. *애플리케이션 배포 = DB 마이그레이션 = 한 번에 적용*. zero-downtime 고려 안 함
- 운영 단계 진입 시 *마이그레이션 → 애플리케이션 배포 → 검증* 의 단계적 적용은 별도 task 에서 정의
