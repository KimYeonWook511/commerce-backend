-- 환불의 첫 상태 이름을 REQUESTED 에서 READY 로 옮긴다. 이 상태는 결제사를 아직 부르지 않았다는
-- 뜻인데 옛 이름은 이미 요청했다는 뜻으로 읽혀, 돈이 나갔는지를 반대로 판단하게 했다.
--
-- enum 이 VARCHAR 로 저장되므로 이미 쌓인 행의 값도 함께 옮긴다. 안 옮기면 발송 대상 조회가
-- 그 행들을 못 찾아 아직 안 나간 환불이 영영 발송되지 않는다.

UPDATE `tbl_refund` SET `status` = 'READY' WHERE `status` = 'REQUESTED';
