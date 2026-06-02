-- tbl_payment_attempt.payment_id를 pg_payment_id로 rename한다.
-- Payment.pgPaymentId와 같은 의미인데 entity별로 표현이 달라 cross-entity 가독성과 의도가 어긋나 있어 도메인 표현을 통일한다 (issue #194).
-- CHANGE COLUMN으로 컬럼명을 바꾸고, unique key 이름도 새 컬럼명을 반영하도록 RENAME INDEX로 메타데이터만 갱신한다.

ALTER TABLE `tbl_payment_attempt`
  CHANGE COLUMN `payment_id` `pg_payment_id` varchar(64) NOT NULL,
  RENAME INDEX `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`
    TO `uk_payment_attempt_merchant_pay_key_provider_pg_payment_id_type`;
