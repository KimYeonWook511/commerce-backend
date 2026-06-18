# payment-order-decouple — 구조

## 현재 구조 (변경 전)

승인 확정의 진입점이 둘이고, 각자 `SucceedPaymentApprovalService.succeedApproval()`을 직접 호출한다.

- **실시간 승인** `ApproveNaverPayUseCase.completeVerifiedApproval()` — `succeedApproval` 시도 후
  거부되면 catch switch에서 `PaymentException`의 errorCode로 보상을 고르고, 예외를 다시 던져
  사용자에게 에러로 응답한다. 주문 상태 거부(`ORDER_PAID_NOT_ALLOWED`)는 사전 가드
  (`existsUnknownByOrderId`/`existsApprovedByOrderId`)로 회피하거나 전파한다.
- **대사** `ReconcilePaymentUseCase.executeApprove()` — `succeedApproval` 거부 시
  `handleOrderNotCompletable()`로 들어가 **주문을 재조회하고 `order.getStatus()`로 4분기**한다
  (`CANCELED`→보상 환불, `PAID`→중복/성공-주체 판별, `null`→통지+fail, 그 외 비-INIT→fail).

`order.getStatus()` 주문 상태 분기는 `handleOrderNotCompletable` 한 곳에만 존재한다. 결제가 주문
상태머신을 대신 도는 셈이라, 주문 상태가 늘면 이 분기가 늘어난다(조합 폭발).

## 변경 후 구조

```
payment/application/usecase/
├── ConfirmApprovalUseCase   ← 신설. provider 중립 조율 facade. @Component, tx 없음.
│     승인 사실 확정 시도 → 성공이면 확정 / 거부면 errorCode로 보상 → Outcome 반환
│     · 두 진입점(실시간·대사)이 공유
│     · order 재조회·getStatus 분기 없음 — 거부 사유는 errorCode로 받는다
│     · 결합은 이 facade 한 점에만 격리 (payment.domain은 order를 모름)
├── ReconcilePaymentUseCase  ← executeApprove가 facade에 위임. handleOrderNotCompletable 해체
└── (CompensateApprovalUseCase — 보상 실행은 그대로, facade가 호출)

payment/naverpay/application/usecase/
└── ApproveNaverPayUseCase   ← completeVerifiedApproval이 facade에 위임
      · PG 프로토콜(approve/history 호출·상태 해석)·검증·사전 가드는 진입점에 유지(provider 특화)
      · facade Outcome을 사용자 응답/예외로 번역
```

## 책임 경계

| 계층/위치 | 책임 |
|---|---|
| 진입점(`ApproveNaverPayUseCase`) | provider 특화 — PG 프로토콜 호출·응답 해석, 검증, 사전 가드, Outcome→HTTP 번역 |
| 진입점(`ReconcilePaymentUseCase`) | 스캔·PG history 조회, Outcome→대사 종착(SUCCEEDED/FAILED) 번역 |
| **facade(`ConfirmApprovalUseCase`)** | **provider 중립** — 승인 사실 확정 시도 + 거부 errorCode별 보상 매핑 |
| service(`SucceedPaymentApprovalService` 등) | tx 단위작업(@Transactional) — payment.succeed + order.completePayment 한 tx |
| domain(`Order.completePayment`) | 주문 상태 해석을 가둔다 — 거부 사유를 errorCode로 표현 |

## 거부 사유 전달 (단방향 결합의 핵심)

`Order.completePayment()`가 INIT이 아닐 때 단일 예외 대신 상태별 errorCode를 던진다
(`ORDER_ALREADY_PAID`/`ORDER_CANCELED_FOR_PAYMENT`/`ORDER_INVALID_STATE_FOR_PAYMENT`). facade는
이 errorCode로 분기하므로 주문을 재조회해 `getStatus()`를 되묻지 않는다. "이 결제가 중복인가"는
주문 상태가 아니라 payment 질문(`existsApprovedByOrderId`)으로 판별한다.

## multi-provider 관점

facade는 provider 공통 "confirm"을 담아 `payment/application/usecase/`(중립 위치)에 둔다. 두 번째
provider가 들어오면 그 진입 UseCase도 같은 facade를 재사용한다. gateway resolver·공통 승인 진입
UseCase·PG 결과 정규화는 그 시점의 후속이며 이번 범위가 아니다.
