# 대사 재조회 backoff를 status-직교 `next_reconcile_at` 필드 + 스캔 게이트로 구현한다

- Status: accepted
- Date: 2026-06-19

## Context

기존 `KEEP_WAITING`(PG가 아직 결론을 못 냄) 분기는 행을 쓰지 않아 같은 행이 매 주기 `id ASC` 첫 페이지를 재점유(누적 시 새 UNKNOWN starvation)하고 매 주기 PG에 재조회됐다(PG API 낭비·Rate Limit, #239).

escalation 종착·통지를 새 상태 대신 `escalated_at` 직교 필드로 표현한 기존 결정(→ PR#242)이 이미 검증한 패턴 — `status` 상태머신을 건드리지 않는 직교 타임스탬프로 부가 시점을 표현한다. `responded_at`을 재사용하면 escalation·stale 윈도우 계산이 오염되므로 별도 필드가 안전하다. NULL을 "즉시 대상"으로 두면 기존 행·신규 행 동작이 보존된다(백필 불필요).

고려한 대안: 스캔 정렬을 시각 기준으로 교체 — 정렬만으로는 PG 반복 조회를 못 줄이고 게이트가 더 단순하다. `responded_at` 재사용 — 계산 오염으로 기각.

## Decision

`KEEP_WAITING`으로 판정된 대사 후보의 재조회를 미루기 위해, `tbl_payment`에 `status`와 무관한 직교 필드 `next_reconcile_at`(V10)을 추가한다. 대사 스캔 쿼리(APPROVE·CANCEL stale)는 `next_reconcile_at IS NULL OR next_reconcile_at <= now` 게이트로 backoff 중인 행을 제외한다. set은 도메인 메서드 `Payment.delayReconcile(now, backoff)`(상태 전이 없음) + `@Version` 낙관 락으로 수행한다.

## Consequences

- 컬럼 1개와 wait 시 write 1회(기존 no-op 대비)가 추가된다. 그러나 그 write가 starvation·PG 반복 조회를 동시에 해소한다.
- 대사 스캔 윈도우를 정한 기존 결정(→ PR#237)과 `@Version` 충돌 처리의 기존 결정(→ PR#245) 위에서 동작한다.
