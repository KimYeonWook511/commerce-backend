-- tbl_order, tbl_payment의 가맹점 결제 키와 PG 결제 ID 길이를 tbl_payment_attempt와 동일하게 64로 맞춘다.
-- 동일 의미 컬럼이 entity별로 서로 다른 길이를 가진 cross-entity 부채를 제거한다 (issue #178).

ALTER TABLE `tbl_order`
  MODIFY COLUMN `merchant_pay_key` varchar(64) DEFAULT NULL;

ALTER TABLE `tbl_payment`
  MODIFY COLUMN `merchant_pay_key` varchar(64) DEFAULT NULL;

ALTER TABLE `tbl_payment`
  MODIFY COLUMN `pg_payment_id` varchar(64) DEFAULT NULL;
