# standalone CANCEL 결제 대사를 신설해 환불을 보장하고, FAILED는 escalation으로 surface한다

- Status: accepted
- Date: 2026-06-18

## Context

기존 대사(`ReconcilePaymentUseCase`)는 `type='APPROVE'`만 스캔하고 `CANCEL_RECONCILE` target은 SKIP해, standalone CANCEL을 구동하는 경로가 없었다. 주문 취소와 단일 tx로 영속화한 환불 의도(→ PR#258)가 SUCCEEDED APPROVE에 매달려 어떤 기존 스캔에도 안 걸렸다(안전망 부재).

정책 뼈대(target/flow policy의 CANCEL 분기)는 이미 있고 배선만 죽어 있어, 스캔 쿼리와 reconcile 루프의 CANCEL 처리만 추가하면 죽은 정책이 live가 된다. 새 정책·새 PG 로직 없이 기존 cancel 상태전이 service·`getApprovalHistory`를 재사용한다. FAILED는 같은 요청 재전송으로 안 풀리는 거절이라 자동 재시도가 아닌 통지가 맞다(자동 재처리 엔진은 #208 item-3으로 분리).

## Decision

`type=CANCEL ∧ status∈{REQUESTED, UNKNOWN}`인 stale CANCEL을 스캔해 PG 재조회·재실행으로 종착시키는 대사 경로를 신설한다. FAILED(확정적 환불 실패)는 자동 재시도하지 않고 escalation 통지로 사람에게 넘긴다.

## Consequences

대사 스캔이 한 종류(CANCEL) 늘어 PG 조회 부하가 증가한다. APPROVE와 동일 cutoff·페이징 정책을 따른다.

관련: unknown-reconciliation task, payment-escalation task, #208.
