-- tbl_payment_reservation에 낙관적 락용 version 컬럼을 추가한다.
-- 같은 예약의 동시 이중 use를 @Version 낙관적 락으로 감지하기 위함이다.
-- 기존 행은 DEFAULT 0으로 채워지며, 이후 JPA가 쓰기 시점에 자동 증가한다.

ALTER TABLE `tbl_payment_reservation`
  ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0;
