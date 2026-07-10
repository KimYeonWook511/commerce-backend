# 사용자 주도 환불은 approve 결제를 FAILED로 만들지 않고 CANCEL 레코드로만 표현한다

- Status: accepted
- Date: 2026-06-18

## Context

보상(compensation) 경로(→ PR#125)의 `runPgCancel`은 "애초에 승인되면 안 됐던" 결제를 되돌리므로 approve를 FAILED로 마킹한다. 사용자 주도 취소는 정당하게 성공한 결제를 환불하는 것이라 의미가 다르다.

결제 테이블은 사건을 쌓는 불변 원장이다. 승인 성공과 환불은 별개 사실이며, 승인 사실을 훼손하지 않아야 감사·분쟁 대응과 부분취소(미래) 확장에서 일관된다. "결제취소 했는가"는 CANCEL 레코드 존재·상태로 판단한다. 보상 경로 회귀 위험을 피하려 `runPgCancel`을 쪼개지 않고 별도 환불 실행 경로를 둔다.

## Decision

PAID 취소 환불에서 대상 APPROVE 결제의 SUCCEEDED 상태는 그대로 두고, 환불은 별도 CANCEL 결제 레코드(append-only)로만 표현한다.

## Consequences

"이 주문 결제가 지금 유효한가"는 APPROVE·CANCEL 레코드를 집계해야 안다(append-only 원장의 일관된 비용).

관련: payment-order-redesign task(append-only 원장 설계).
