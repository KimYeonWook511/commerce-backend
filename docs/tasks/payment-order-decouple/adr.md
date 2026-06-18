# payment-order-decouple — Task ADR (staging)

이 task에서 새로 채택된 결정의 staging이다(임시 번호 L1·L2…). harness Stage 8(Root Sync)에서
루트 전역 번호(ADR-XXXX)로 `docs/adr.md`에 append한다.

---

## ADR-L1: 결제 승인 조율을 provider 중립 facade로 모으고 결제→주문 단방향 결합으로 정리한다

- **결정**: 여러 도메인을 엮는 승인 확정 흐름(승인 사실 확정 + 거부 보상)을 `payment/application/usecase/`의
  provider 중립 조율 UseCase(`ConfirmApprovalUseCase`)로 모은다. 실시간 승인·대사 두 진입점이 이 facade를
  공유한다. facade는 tx를 열지 않고(@Component, ADR-054/055) tx 단위작업은 기존 `…Service`에 위임한다.
  결합은 facade 한 점에만 격리한다 — `payment.domain`은 order를 모르고, `order.domain`은 payment를 모른다.
- **배경**: 결제 대사·승인이 `order.getStatus()`로 주문 상태별 분기를 결제 쪽에서 돌려, 주문 상태가 늘면
  결제 분기가 폭발했다(PR #237 H1·M1·dead 분기의 산물).
- **이유**: 조율자를 한 점에 두면 각 도메인은 자기 일만 한다. 트리거(PG 승인 응답·대사 스캔)가 모두 결제
  쪽이므로 조율자도 payment.application에 둔다. provider 중립 위치라 두 번째 provider 진입점도 재사용한다.
- **트레이드오프**: facade가 order errorCode에 의존하지만(거부 사유 해석) 그 의존은 한 점에 격리된다.
  별도 조율 패키지는 흐름이 하나뿐이라 YAGNI — 적립·쿠폰 등이 더 엮이면 그때 승격한다.
- **연계**: ADR-026(Order↔Payment 경계), ADR-054/055, #240, #237.

## ADR-L2: order.completePayment 거부 사유를 errorCode로 세분화해 주문 상태 재조회 분기를 제거한다

- **결정**: `Order.completePayment()`가 INIT이 아닐 때 단일 `ORDER_PAID_NOT_ALLOWED` 대신 상태별 errorCode를
  던진다 — `PAID`→`ORDER_ALREADY_PAID`, `CANCELED`→`ORDER_CANCELED_FOR_PAYMENT`, 그 외 비-INIT→
  `ORDER_INVALID_STATE_FOR_PAYMENT`. facade는 이 errorCode로 분기하고 주문을 재조회해 `getStatus()`를 되묻지
  않는다. "이 결제가 중복인가"는 주문 상태가 아니라 payment 질문(`existsApprovedByOrderId`)으로 판별한다.
  주문 자체가 없는 경우는 `completePayment` 이전 `findByIdForUpdate`에서 `ORDER_NOT_FOUND`로 던져지며, facade가
  이를 직접 받아 환불 없이 정합성 통지 + FAILED 종착으로 둔다(옛 `handleOrderNotCompletable`의 별도 재조회 기반
  order==null 처리를 대체, ADR-049 보존).
- **배경**: 기존 대사 `handleOrderNotCompletable`은 거부를 받고 주문을 재조회해 `order.getStatus()`로 4분기했다.
  주문 상태 해석이 결제 쪽에 흩어져 있었다.
- **이유**: 주문 상태 해석을 주문 메서드 안에 가두고(Tell-Don't-Ask), 거부 결과만 errorCode로 전달하면 결제는
  주문 상태머신을 알 필요가 없다. 실시간 승인이 이미 errorCode로 보상을 고르는 방식과 스타일이 통일된다.
- **트레이드오프**: OrderErrorCode가 늘어난다. 그러나 주문 상태가 늘어도 facade 분기는 errorCode 단위로만
  늘고, 결제가 주문 상태를 재조회하는 결합은 사라진다.
- **연계**: ADR-L1, ADR-048(supersede — ADR-L3 참조), #240.

## ADR-L3: PAID 성공-주체 확정 분기를 제거하고 비중복 PAID는 통지+fail 안전망으로 둔다 (ADR-048 supersede)

- **결정**: 대사의 PAID 분기에서 "성공-주체→`succeedApprovalRecordOnly`로 SUCCEEDED 맞춤"을 제거한다. PAID 거부는
  `existsApprovedByOrderId`로 판별해 **중복(true)이면 보상 환불**, **비중복(false)이면 환불하지 않고 정합성
  통지 + FAILED 종착**으로 둔다. dead 코드 `SucceedPaymentApprovalRecordService`/`succeedApprovalRecordOnly`를
  삭제한다.
- **배경**: ADR-048은 PAID 분기를 "없으면 이 건이 성공 주체→SUCCEEDED 맞춤"으로 결정했다. 그러나 주문이 PAID가
  되는 경로는 `succeedApproval`의 한 tx(`payment.succeed`+`saveApproved`+`order.completePayment`)뿐이고,
  `uk_payment_approved_order_key`(주문당 SUCCEEDED APPROVE 1개)가 두 번째 결제를 `completePayment` 도달 전
  `PAYMENT_DUPLICATE`로 막는다. 따라서 "주문 PAID인데 SUCCEEDED APPROVE 없음"(성공-주체 분기 조건)은 모순 =
  도달 불가능한 dead 코드다.
- **이유**: dead 확정 경로를 남기면 코드가 거짓 의미를 갖는다. 동시에, 만에 하나 그 상태에 도달하면 성공-주체를
  환불해버리는 사고를 막아야 하므로(금전 정합성은 희박한 경합도 안전하게) 환불 대신 통지+fail로 종착시킨다.
  ADR-043(취소 주문 보상 환불)·ADR-049(order==null 통지) 동작은 facade가 그대로 보존한다.
- **트레이드오프**: 비-INIT 종착이라는 ADR-048의 정신은 유지하되, ① PAID 성공-주체 SUCCEEDED 맞춤을 통지+fail로
  바꾸고 ② 종착 판단을 `order.getStatus()` 분기에서 errorCode 분기로 옮긴다.
- **supersedes**: ADR-048(대사 중 비-INIT 주문 종착 — PAID 성공-주체 SUCCEEDED 맞춤 + getStatus 분기 부분).
- **연계**: ADR-039/044(status는 사실만), ADR-043, ADR-049, #237, #240.

## ADR-L4: gateway resolver·공통 승인 진입 UseCase는 이번에 도입하지 않는다

- **결정**: provider별 PG 프로토콜을 추상화하는 gateway resolver, 공통 승인 진입 UseCase, PG 결과 정규화 레이어는
  이번 범위에서 만들지 않는다. provider 특화 진입점(`ApproveNaverPayUseCase`)은 PG 프로토콜 흐름을 담는 진입점으로
  유지하고, 그 안의 provider 공통 "confirm"만 facade로 추출한다.
- **배경**: 결제 provider가 NaverPay 하나뿐이다. NaverPay 승인은 `ready→approve(redirect)`·`ALREADY_COMPLETE`
  같은 특유의 상태머신을 갖고, 카카오/토스는 또 다르다.
- **이유**: 정규화 경계는 두 번째 provider의 실제 모양을 봐야 제대로 그어진다. 가상의 provider로 추상화하면
  NaverPay 한 곳에만 맞는 틀린 추상이 나온다(YAGNI, "맥락이 달라지는 시점에 분리" 원칙). 다만 facade를 provider
  중립 위치에 둠으로써, 두 번째 provider 진입점이 같은 facade를 재사용할 토대는 미리 깔아둔다.
- **트레이드오프**: 두 번째 provider 도입 시 진입 UseCase 신설·gateway 추상화·결과 정규화가 후속 작업으로 남는다.
- **연계**: ADR-L1, ADR-045(채널 adapter 후속 분리 — 점진적 분리 동형), #240.
