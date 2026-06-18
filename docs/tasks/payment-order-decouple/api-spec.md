# payment-order-decouple — API 스펙

## 변경 없음

이 task는 내부 구조 리팩터(결제-주문 결합 제거·조율 facade 도입)다. 외부 API 계약
(엔드포인트·요청·응답·실패코드)은 변경하지 않는다.

- 결제 예약 `POST /payments/reserve`, NaverPay 승인 `POST /payments/naverpay/approve` 등의
  엔드포인트·요청·응답 스키마는 그대로다.
- 실시간 승인 거부 시 사용자에게 반환하는 errorCode 응답 동작도 보존한다(중복·금액 불일치 등의
  기존 `PaymentException` 응답을 그대로 유지 — 회귀 금지).

> `Order.completePayment()`가 던지는 errorCode 세분화(`ORDER_ALREADY_PAID` 등)는 내부 도메인 신호이며
> HTTP 응답 계약으로 노출되지 않는다(승인 확정 경로의 내부 거부 사유).
