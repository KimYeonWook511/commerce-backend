# payment-order-decouple — 결제-주문 결합 제거와 결제 조율 facade 도입

## 배경

결제 대사·승인 흐름이 `order.getStatus()`를 직접 읽고 주문 상태별로 분기한다. 특히
`ReconcilePaymentUseCase.handleOrderNotCompletable`이 `CANCELED`/`PAID`/`null`/비-INIT의 4분기로
주문 상태머신을 결제 쪽에서 대신 돌린다. 주문 상태가 하나 늘면 결제 분기가 배로 늘어나는
조합 폭발 구조이며, PR #237에서 드러난 중복 결제 보상 분기·PAID dead 분기의 산물이기도 하다.

## 문제

- 결제가 주문 내부 상태에 결합돼 책임 경계가 모호하다. 결제 스펙이 주문 변화에 끌려다닌다.
- 실시간 승인(`ApproveNaverPayUseCase`)과 대사(`ReconcilePaymentUseCase`)의 거부 처리 방식이
  비대칭이다 — 실시간은 errorCode로 보상을 고르고, 대사만 거부를 받고 주문을 재조회해
  `getStatus()`로 사유를 되묻는다.
- `handleOrderNotCompletable`의 PAID 성공-주체 분기(`succeedApprovalRecordOnly`)는
  `uk_payment_approved_order_key`(주문당 SUCCEEDED APPROVE 1개) 제약상 도달 불가능한 dead 코드다.

## 목표

- 결제는 주문 상태를 묻지 않고 "결제 사실"만 만든다. 주문 상태 해석은 주문 메서드
  (`completePayment`) 안으로 수렴하고, 결제→주문 단방향 결합으로 정리한다.
- 여러 도메인을 엮는 승인 확정 흐름을 **provider 중립 조율 facade** 하나로 모은다. 결합은
  facade 한 점에만 격리한다.
- 거부 사유를 `order.getStatus()` 재조회 대신 errorCode로 전달해 `handleOrderNotCompletable`의
  주문 상태 분기를 제거한다.
- dead 확정 코드를 제거하고, 만약의 비중복 PAID는 환불 대신 통지+fail 안전망으로 둔다.

## 범위

- `Order.completePayment()`의 거부 사유 errorCode 세분화.
- provider 중립 confirm facade(`payment/application/usecase/`) 신설.
- 대사 경로를 facade로 위임하고 `handleOrderNotCompletable` 해체.
- 실시간 NaverPay 승인 경로를 같은 facade로 위임(확정+보상만 공통화, PG 프로토콜은 진입점 유지).
- dead 확정 코드(`succeedApprovalRecordOnly`) 제거 + 비중복 PAID 통지+fail 안전망.

## 범위 밖 (후속)

- **gateway resolver / 공통 승인 진입 UseCase / PG 결과 정규화**: provider가 NaverPay 하나뿐인
  현재는 도입하지 않는다(YAGNI). 두 번째 provider(카카오/토스 등)가 실제로 들어오는 시점에,
  그때의 실제 PG 차이를 보고 정규화 경계를 그린다. 이번 facade는 그 미래 구조의 "provider 공통
  confirm" 절반을 미리 깔아두는 역할이다.
- 결제 도메인의 다른 배치/스케줄러 대상(#208 Epic의 나머지 item).

## 검증 기준

- 결제 코드에 `order.getStatus()` 분기가 없다(주문 상태 판단은 주문 안으로 수렴).
- 대사·승인 흐름이 facade 조율로 단순화되고, dead 분기가 없다.
- 발생하는 상황(취소 주문 보상 환불·중복 결제 보상)의 정합성, 발생 안 하는 상황(성공-주체 PAID)의
  불가능성이 테스트로 보장된다.
- 비중복 PAID는 자동 환불 없이 통지+fail로 종착한다.
- 실시간 승인의 사용자 대면 에러 응답 동작이 보존된다(회귀 없음).

## 관련

- 이슈 #240, PR #237(이 결합의 산물 H1·M1·dead 분기)
- Epic #208(결제 도메인 배치/스케줄러 대상)
