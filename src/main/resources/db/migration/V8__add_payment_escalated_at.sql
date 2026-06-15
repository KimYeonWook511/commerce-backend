-- tbl_payment에 escalation(운영 위임) 시각을 담는 escalated_at 컬럼을 추가한다.
-- NULL이면 미escalation, 값이 있으면 이미 운영자에게 통지·종착된 건이다.
-- nullable 추가라 기존 행 백필 불필요 (기존 행은 NULL = 미escalation).

ALTER TABLE `tbl_payment`
  ADD COLUMN `escalated_at` DATETIME(6) NULL;
