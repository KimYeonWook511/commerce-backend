-- 결제 도메인 재설계: PaymentReservation 도입 및 PaymentAttempt → Payment rename
-- tbl_payment (성공 결제 1:1 구조) 삭제, tbl_payment_attempt → tbl_payment rename,
-- tbl_order.merchant_pay_key 제거, tbl_payment_reservation 신규 생성
--
-- [경고] 파괴적 마이그레이션 — 운영 데이터 없음(pre-prod) 전제 (ADR-7).
-- DROP TABLE tbl_payment 으로 기존 성공 결제 이력을 영구 폐기하고, order_id 를 backfill 없이
-- NOT NULL 로 추가한다. 기존 데이터가 있는 DB 에 그대로 적용하면 이력 손실 또는 ADD COLUMN 실패가 발생한다.
-- 운영 단계 진입 시에는 별도 backfill task 가 필요하다: order_id 를 NULL 로 먼저 추가 → merchant_pay_key 로
-- 역조회해 backfill → NOT NULL 승격, tbl_payment_reservation 역산 INSERT, 옛 tbl_payment 이관 후 DROP.

-- 1. tbl_order에서 merchant_pay_key unique key 및 컬럼 제거
ALTER TABLE `tbl_order`
  DROP INDEX `uk_order_merchant_pay_key`,
  DROP COLUMN `merchant_pay_key`;

-- 2. 기존 tbl_payment (성공 결제 1:1) 테이블 삭제
DROP TABLE `tbl_payment`;

-- 3. tbl_payment_attempt를 tbl_payment로 rename (PG 이벤트 단위 append-only 이력)
RENAME TABLE `tbl_payment_attempt` TO `tbl_payment`;

-- 4. tbl_payment에 order_id, approved_order_key 컬럼 추가 및 unique key 재구성
ALTER TABLE `tbl_payment`
  DROP INDEX `uk_payment_attempt_merchant_pay_key_provider_pg_payment_id_type`,
  ADD COLUMN `order_id` BIGINT NOT NULL,
  ADD COLUMN `approved_order_key` BIGINT NULL,
  -- NULL trick: APPROVE+SUCCEEDED일 때만 orderId 값이 채워져 unique 제약이 동작
  ADD UNIQUE KEY `uk_payment_approved_order_key` (`approved_order_key`),
  ADD UNIQUE KEY `uk_payment_merchant_pay_key_provider_pg_payment_id_type` (`merchant_pay_key`, `provider`, `pg_payment_id`, `type`),
  ADD INDEX `idx_payment_order` (`order_id`);

-- 5. tbl_payment_reservation 테이블 신규 생성
CREATE TABLE `tbl_payment_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `amount` int NOT NULL,
  `provider` varchar(32) NOT NULL,
  `merchant_pay_key` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  -- NULL trick: RESERVED 상태이면 "{orderId}:{provider.name()}" 값, USED 이후이면 null
  `reserved_key` varchar(96) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_reservation_merchant_pay_key` (`merchant_pay_key`),
  UNIQUE KEY `uk_payment_reservation_reserved_key` (`reserved_key`),
  INDEX `idx_reservation_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
