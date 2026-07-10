# backoff write는 wait로 끝나는 분기에만 적용하고, 상태 확정 경로는 자기 cadence를 따른다

- Status: accepted
- Date: 2026-06-19

## Context

상태를 확정하는 분기는 이미 행을 쓰며(예: markUnknown이 `responded_at=now` 갱신) 자기 cadence로 재진입을 늦춘다. 거기에 backoff까지 더하면 의미가 중복되고 두 시점 필드가 경합한다. `delayReconcile`은 `status`를 읽지도 바꾸지도 않으므로 wait 분기에 도달하는 어떤 status(UNKNOWN/REQUESTED, 일부 FAILED CANCEL 포함)에도 안전하다.

고려한 대안: 모든 대사 outcome에 일괄 backoff — 상태 확정 분기와 cadence가 중복돼 기각.

## Decision

같은 PR에서 도입한 `next_reconcile_at` backoff는 PG 조회가 "아직 대기"로 끝나는 분기(APPROVE `KEEP_WAITING`, CANCEL `KEEP_WAITING`·재시도 `PROCESSING`)에서만 기록한다. succeed/fail/markUnknown처럼 `status`를 쓰는 분기에는 추가하지 않는다.

## Consequences

- backoff write는 `@Version`을 거치며, 동시 전이가 먼저 행을 바꿔 `PAYMENT_CONCURRENTLY_MODIFIED`·행 없음이 나면 tx 밖에서 skip한다. backoff는 best-effort cadence 힌트라 충돌 시 다음 주기에 자연히 재시도된다(기존 `*Skippable` 패턴 동일). 상세는 `docs/optimistic-lock-design.md`.
