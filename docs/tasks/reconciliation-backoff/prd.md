# PRD — 대사 스캔 KEEP_WAITING backoff

## 배경

결제 대사 스케줄러(`ReconcilePaymentUseCase.reconcile()`)는 매 1분 stale APPROVE/CANCEL 결제를
`id ASC` 첫 페이지(batch size)만 스캔해 PG에 실제 상태를 물어본다. PG가 아직 결론을 못 내면
(PENDING/NOT_FOUND) `KEEP_WAITING`으로 판정되는데, 이때 **결제 행을 전혀 쓰지 않는다**. 그래서
같은 행이 다음 주기에도 스캔 윈도우에 그대로 남아 `id ASC` 첫 페이지를 다시 차지한다.

이슈 #239가 제기한 두 문제 중, 이미 대응된 부분과 남은 부분을 구분한다.

- **이미 대응됨**: 스캔 윈도우 상한(`escalationCutoff` 6시간)으로 영구 정체 행이 윈도우를 영원히
  점유하던 경로는 닫혔고, REQUESTED 하한 분리(15분)로 미성숙 REQUESTED가 첫 페이지를 차지하던
  경로도 닫혔다.
- **남은 본체(이 작업)**: `KEEP_WAITING` 행이 윈도우(1분~6시간) 안에서 batch size 이상 누적되면
  `id ASC`상 매 주기 첫 페이지를 점유해 **뒤의 새 UNKNOWN 건이 starvation**된다. 또 같은 행을
  **매 1분마다 PG에 반복 조회**해 PG API 낭비·Rate Limit 위험이 있다.

## 목표

`KEEP_WAITING`으로 판정된 대사 후보의 **재조회를 일정 시간 미뤄(backoff)**, 누적된 대기 건이 새
후보를 굶기지 않게 하고 같은 건의 PG 조회 빈도를 줄인다.

## 범위

포함:
- 결제 행에 `status`와 무관한 직교 필드 `next_reconcile_at`을 추가한다(`escalated_at` 패턴 차용).
- 스캔 쿼리(APPROVE·CANCEL stale)에 backoff 게이트를 추가해 `next_reconcile_at`이 미래인 행을
  제외한다. NULL(한 번도 미뤄지지 않음)은 즉시 대상이라 기존 동작을 보존한다.
- 대사 wait 결과(APPROVE `KEEP_WAITING`, CANCEL `KEEP_WAITING`·재시도 `PROCESSING`)에서
  `next_reconcile_at`을 `now + 고정 backoff`로 갱신한다(상태 전이 없음).

제외:
- 지수(점증) backoff. 단일 고정 간격으로 시작한다(과설계 방지, 운영 config 승격 전제).
- FAILED CANCEL의 자동 재시도·escalation 고도화(#260, #208 item-3). 이 작업은 wait 재조회
  cadence만 다룬다.
- 스캔 정렬 정책 변경. backoff가 "최근 본 건"을 스캔에서 빼므로 `id ASC` 유지로 starvation이
  해소된다(정렬 교체 불필요).

## 성공 기준

- `KEEP_WAITING`이 batch size 이상 누적된 상황에서도 새 UNKNOWN 후보가 스캔에서 차단되지 않는다.
- 동일 건의 PG 조회 빈도가 backoff 간격만큼 감소한다.
- 상태를 확정하는 경로(succeed/fail/markUnknown)와 escalation 동작은 회귀하지 않는다.
- APPROVE·CANCEL 양쪽에 일관되게 적용된다.

## 관련

- 이슈 #239 (대사 스캔 starvation·backoff)
- `docs/tasks/unknown-reconciliation/` (대사 스캔 루프·escalation 스캔 윈도우 상한)
- `docs/tasks/payment-escalation/` (`escalated_at` status-직교 필드 패턴, ADR-049)
- `docs/tasks/paid-order-cancel-refund/` (standalone CANCEL 대사, ADR-059)
