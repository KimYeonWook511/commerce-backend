# PAID 성공-주체 확정 분기를 제거하고 비중복 PAID는 통지+fail로 둔다

- Status: accepted
- Date: 2026-06-18

## Context

대사 중 주문이 비-INIT이면 건너뛰지 않고 종착 상태로 전이하는 기존 결정(→ PR#237)은 PAID 분기를 "SUCCEEDED APPROVE가 없으면 이 건이 성공 주체→SUCCEEDED 맞춤"으로 결정했다. 이 ADR은 그 결정에서 PAID 성공-주체 SUCCEEDED 맞춤과 `getStatus()` 분기 부분을 제거한다. 비-INIT 종착·취소/중복 보상 정신은 유지한다.

주문이 PAID가 되는 경로는 `succeedApproval`의 한 tx(`payment.succeed`+`saveApproved`+`order.completePayment`)뿐이고, `uk_payment_approved_order_key`(주문당 SUCCEEDED APPROVE 1개)가 두 번째 결제를 `completePayment` 도달 전 `PAYMENT_DUPLICATE`로 막는다. 따라서 "주문 PAID인데 SUCCEEDED APPROVE 없음"(성공-주체 분기 조건)은 모순 = 도달 불가능한 dead 코드다.

dead 확정 경로를 남기면 코드가 거짓 의미를 갖는다. 동시에 만에 하나 그 상태에 도달하면 성공-주체를 환불해버리는 사고를 막아야 하므로(금전 정합성은 희박한 경합도 안전하게) 환불 대신 통지+fail로 종착시킨다. (#237, #240)

## Decision

대사 PAID 분기에서 "성공-주체→SUCCEEDED 맞춤"을 제거한다. PAID 거부는 `existsApprovedByOrderId`로 판별해 **중복(true)이면 보상 환불**, **비중복(false)이면 환불하지 않고 정합성 통지 + FAILED 종착**으로 둔다. dead 코드 `SucceedPaymentApprovalRecordService`/`succeedApprovalRecordOnly`를 삭제한다.

## Consequences

- 대사가 승인 확정한 결제의 주문이 이미 취소됐으면 보상 환불하는 기존 결정(→ PR#237)과 주문 미존재 시 통지하는 기존 결정(→ PR#242)의 동작은 같은 PR에서 도입한 facade가 그대로 보존한다.
- 보상된 APPROVE 결제 상태를 FAILED로 유지하는 기존 결정(→ PR#236)과 대사 종착에 새 결제 상태를 도입하지 않는 기존 결정(→ PR#237)의 연장선에 있다.
