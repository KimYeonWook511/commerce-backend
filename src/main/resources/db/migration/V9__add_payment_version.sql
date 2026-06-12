-- tbl_payment에 낙관적 락용 version 컬럼을 추가한다.
-- 같은 Payment 행을 동시에 read-modify-write로 전이하는 경합(succeed vs fail 등)에서
-- lost update를 @Version으로 감지하기 위함이다 (ADR-L1).
-- 기존 행은 DEFAULT 0으로 채워지며, 이후 JPA가 쓰기 시점에 자동 증가한다.

ALTER TABLE `tbl_payment`
  ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0;
