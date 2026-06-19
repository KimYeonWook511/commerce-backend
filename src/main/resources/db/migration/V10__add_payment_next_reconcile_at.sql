-- tbl_payment에 대사 재조회 backoff 시각을 담는 next_reconcile_at 컬럼을 추가한다.
-- NULL이면 한 번도 미뤄지지 않은 즉시 대사 대상. 값이 있으면 해당 시각 이후에 재조회 대상이 된다.
-- nullable 추가라 기존 행 백필 불필요 (기존 행은 NULL = 즉시 대상, escalated_at 패턴 동일).

ALTER TABLE `tbl_payment`
  ADD COLUMN `next_reconcile_at` DATETIME(6) NULL;
