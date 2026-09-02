-- 결제 행이 들고 있던 환불 금액 합계 하나를 둘로 가른다. 옛 값에는 "돌려주기로 했는데 아직 안 나간
-- 돈"과 "실제로 돌아간 돈"이 섞여 있어 돈이 얼마나 움직였는지를 물을 수단이 없었다.
--
-- 순서가 있다. 컬럼을 먼저 만들고 그 다음에 채운다.

-- 옛 이름은 -ed 때문에 "이미 돌려준 돈"으로 읽히는데 실제로는 아직 안 나간 것까지 포함한다.
-- 값·타입·기본값은 그대로이고 이름만 옮긴다.
ALTER TABLE `tbl_payment`
  RENAME COLUMN `total_refunded_amount` TO `refund_opened_amount`;

-- 결제사 응답으로 성공이 확정된 환불 금액의 합. 조회 조건으로 쓰지 않아 인덱스를 두지 않고,
-- 앞 컬럼과의 관계도 데이터베이스 제약으로 두지 않는다 — 위반은 거부될 뿐 아니라 경합과 구분되는
-- 신호로 남아야 해서 도메인 가드가 그 자리를 맡는다.
ALTER TABLE `tbl_payment`
  ADD COLUMN `refund_succeeded_amount` int NOT NULL DEFAULT 0 AFTER `refund_opened_amount`;

-- 결제마다 그 결제에 딸린 성공한 환불만 더한다. 결제별로 묶는 조건이 빠지면 모든 결제가 전체 합계를
-- 받는데, 그렇게 되면 한 결제가 남의 환불 합을 들어 그 결제의 확정이 영구히 막힌다.
-- 성공한 환불이 없는 결제에는 0이 들어가 기본값과 같아진다.
UPDATE `tbl_payment` p
SET p.`refund_succeeded_amount` = (
  SELECT COALESCE(SUM(r.`amount`), 0)
  FROM `tbl_refund` r
  WHERE r.`payment_id` = p.`id`
    AND r.`status` = 'SUCCEEDED'
);
