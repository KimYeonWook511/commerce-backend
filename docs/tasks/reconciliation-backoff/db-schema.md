# DB Schema — 대사 스캔 KEEP_WAITING backoff

실제 DDL의 단일 출처는 Flyway V스크립트다. 이 문서는 이번 작업의 스키마 변경만 기술한다.

## `tbl_payment` — `next_reconcile_at` 컬럼 추가 (V10)

```sql
ALTER TABLE `tbl_payment`
  ADD COLUMN `next_reconcile_at` DATETIME(6) NULL;
```

- `next_reconcile_at DATETIME(6) NULL` — 다음 대사 재조회 가능 시각. NULL이면 한 번도 미뤄지지
  않은 즉시 대사 대상. `status`와 무관한 직교 필드로 `escalated_at`(V8)과 같은 성격이다.
- nullable 추가라 기존 행 백필이 필요 없다(기존 행은 NULL = 즉시 대상).

## 스캔 쿼리 영향

- `findStaleApprovePaymentsForReconciliation` / `findStaleCancelPaymentsForReconciliation`의
  WHERE에 backoff 게이트가 추가된다:
  `AND (p.next_reconcile_at IS NULL OR p.next_reconcile_at <= :now)`.
- escalation 후보 조회(`find*EscalationCandidates`)는 변경하지 않는다.
- 인덱스는 추가하지 않는다. 스캔은 batch size 첫 페이지 한정이고 게이트는 보조 조건이므로 초기엔
  과설계를 피한다(필요 시 후속에서 평가).
