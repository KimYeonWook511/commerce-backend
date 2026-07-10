# order.completePayment 거부 사유를 errorCode로 세분화해 주문 상태 재조회 분기를 제거한다

- Status: accepted
- Date: 2026-06-18

## Context

기존 대사 `handleOrderNotCompletable`은 거부를 받고 주문을 재조회해 `order.getStatus()`로 4분기했다. 주문 상태 해석이 결제 쪽에 흩어져 있었다(#240).

주문 상태 해석을 주문 메서드 안에 가두고(Tell-Don't-Ask) 거부 결과만 errorCode로 전달하면 결제는 주문 상태머신을 알 필요가 없다. 실시간 승인이 이미 errorCode로 보상을 고르는 방식과 통일된다.

## Decision

`Order.completePayment()`가 INIT이 아닐 때 단일 `ORDER_PAID_NOT_ALLOWED` 대신 상태별 errorCode를 던진다(`ORDER_ALREADY_PAID`/`ORDER_CANCELED_FOR_PAYMENT`/`ORDER_INVALID_STATE_FOR_PAYMENT`). 같은 PR에서 도입한 승인 확정 facade는 이 errorCode로 분기하고 주문을 재조회해 `getStatus()`를 되묻지 않는다. "이 결제가 중복인가"는 주문 상태가 아니라 payment 질문(`existsApprovedByOrderId`)으로 판별한다. 주문 자체가 없는 경우는 `completePayment` 이전 단계에서 `ORDER_NOT_FOUND`로 나오며 facade가 환불 없이 통지+FAILED 종착으로 둔다(주문 미존재 시 통지하는 기존 결정(→ PR#242) 보존).

## Consequences

- OrderErrorCode가 늘어난다. 그러나 주문 상태가 늘어도 facade 분기는 errorCode 단위로만 늘고, 결제가 주문 상태를 재조회하는 결합은 사라진다.
