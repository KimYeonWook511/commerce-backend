# 승인 진입에 주문 기준 성공결제 사전 차단을 추가한다

- Status: accepted
- Date: 2026-06-09

## Context

#231. 진입에서 UNKNOWN 만 차단하고 "이미 성공한 APPROVE 결제가 있는 주문"은 차단하지 않아, 한 주문을 다른 reservation(merchantPayKey)으로 재결제하는 새 승인이 PG 호출까지 가서 최종 보루(`uk_payment_approved_order_key`)에서 보상으로 처리됐다.

- **고려한 대안**: 진입 차단 없이 최종 보루에만 의존 — 불필요한 PG 청구→취소 보상이 발생한다.
- **이유**: 첫 결제 성공 이후 들어온 새 승인을 PG 호출 전에 막아 보상 발생 빈도를 낮춘다. 진입 차단 위치는 USED 분기 이후로 두어, 기존 결제 도메인 재설계 결정(→ PR#205)이 정한 USED 예약의 같은 키 redirect 멱등 응답을 가로채지 않는다(진입 차단은 RESERVED 신규 승인에만 적용).

## Decision

`PaymentRepository.existsApprovedByOrderId(orderId)`(= APPROVE·SUCCEEDED 존재 EXISTS)를 추가하고, `NaverPayApprovalService.approve()` 의 USED 분기 이후·`create()` 전에서 호출해 이미 성공 결제가 있는 주문의 새 승인을 `PAYMENT_DUPLICATE` 로 차단한다. 기존 UNKNOWN 차단(`existsUnknownByOrderId`)과 동형이다.

## Consequences

이미 성공 결제가 있는 주문의 새 승인이 진입 단계에서 차단된다. `PAYMENT_DUPLICATE` 는 진입 가드(앞단)와 `uk_payment_approved_order_key` 위반(최종 보루)이 같은 "주문 이중 결제" 사실을 공유하는 코드다. 정합성 자체는 #230 이 최종 보장한다.

관련: 결제 도메인 재설계 결정(→ PR#205), 보상 대상 pgPaymentId 무조건 취소 결정(→ PR#233), #230, #231.
