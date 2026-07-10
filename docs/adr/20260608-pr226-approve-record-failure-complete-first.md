# 정상 승인 후 transient 기록 실패는 환불하지 않고 REQUESTED로 두어 reconcile이 완료시킨다

- Status: accepted
- Date: 2026-06-08

## Context

#225, PR #224 리뷰 #1. 기존 `compensateUnexpected`가 PG SUCCESS + verify 통과한 *맞는 결제*의 transient 기록 실패(DB 데드락 등)를 `FAILED(APPROVE_PROCESS_FAILED)` + PG 환불로 박제했고, "완료가 맞음 / FAILED가 맞음 / 버그"를 한 status로 싸잡았다.

금전 정합 — 정상 매출을 transient로 취소하지 않는다(저확률 경합도 안전 우선). 실시간 경로는 "완료 또는 흔적(REQUESTED) 남김"까지만 책임지고, 결과 확정·복구는 배치 reconcile(`APPROVE_RECONCILE` + PG `PG_APPROVED` → 완료)이 self-heal한다(후처리 status 중심 전환·대사 임계 파생 결정(→ PR#224)과 같은 방향). 진짜 버그도 500으로 가시화한다(예상 못 한 예외 전파).

## Decision

`completeVerifiedApproval`(PG SUCCESS + 키·금액 검증 통과 후 호출)의 unmapped 예외 보상을 제거한다. unmapped `PaymentException`(default)·`CustomException`·일반 `Exception`은 보상 없이 **전파(500)**하고 approve를 `REQUESTED`로 남긴다(완료 우선). 명시적 비정상(`MERCHANT_KEY_MISMATCH`·`AMOUNT_MISMATCH`)은 *틀린 결제*라 현행 보상(환불)을 유지한다. 사용처가 사라진 `compensateUnexpected`를 제거한다.

## Consequences

`REQUESTED` 잔여 회수는 reconcile 구현(#221/#208)에 의존한다 — 그 구현 전까지는 코드 레벨 self-heal 안전망이 없다.

연계: 요청 전송 시점 경계 결정(→ PR#218), 후처리 status 중심 전환·대사 임계 파생 결정(→ PR#224), #221, Epic #208, PR #224 리뷰 #1.
