# 결제 후처리 대상 식별을 failCode 열거에서 status(UNKNOWN/stale REQUESTED) 중심으로 전환한다

- Status: accepted
- Date: 2026-06-08

## Context

#221, PR #224. 기존 후처리 정책(test-side `postprocess` 패키지)은 approve 결과 불명을 `FAILED` + failCode 열거(PG_NETWORK_ERROR 등)로 식별했다. PG 예외를 요청 전송 시점 경계로 가른 결정과 그 확장(→ PR#218, PR#220) 이후 결과 불명은 `FAILED`가 아니라 `status=UNKNOWN`으로 보존되어, 그 failCode 열거가 실제 상태와 매칭되지 않는 죽은 분기가 됐다.

식별 키가 현재 도메인 모델(UNKNOWN 일급)과 일치해야 정책이 실제 상태를 정확히 분류한다. 그 결정들(→ PR#218, PR#220)의 분류축(*재시도 안전성 = PG 처리 가능성*)을 후처리에서도 계승한다 — UNKNOWN/stale=대사, 확정 FAILED=없음.

## Decision

후처리(대사/재시도) 결정 정책의 대상 식별 키를 status 중심으로 둔다. `APPROVE UNKNOWN ∨ stale REQUESTED` → 승인 대사, `CANCEL UNKNOWN ∨ stale REQUESTED ∨ 재시도 가능 FAILED(CANCEL_PROCESS_FAILED·PG_INVALID_RESPONSE)` → 취소 대사, `approve FAILED(AMOUNT_MISMATCH·DUPLICATE_PAYMENT) ∧ cancel 기록 없음` → 취소 보상, `SUCCEEDED·확정 FAILED(TIME_EXPIRED 등)` → 없음. 동시 UNKNOWN(approve+cancel)은 approve를 먼저 확정한다(검사 순서로 인코딩).

## Consequences

approve 결과 불명 failCode 분기는 제거되고, failCode 식별은 cancel 재시도 분류와 mismatch 격리에만 축소되어 남는다.

연계: UNKNOWN 마킹/차단 정책(→ PR#205), 요청 전송 시점 경계 결정과 그 확장(→ PR#218, PR#220), Epic #208 (batch #1 UNKNOWN 대사·#2 stale REQUESTED). 운영 전달 메커니즘(배치/스케줄러/이벤트)·만료↔대사 타이밍(#222)은 범위 밖.
