# 회고 — 결제-주문 결합 제거와 결제 조율 facade 도입

이슈 #240, PR #262.

## 무엇을 만들었나

결제 대사·승인이 `order.getStatus()`로 주문 상태머신을 결제 쪽에서 대신 돌리던 결합을 제거했다. 승인 확정 흐름(승인 사실 확정 + 거부 보상)을 provider 중립 조율 facade(`ConfirmApprovalUseCase`)로 모으고, 실시간 승인(`ApproveNaverPayUseCase`)과 대사(`ReconcilePaymentUseCase`) 두 진입점이 이를 공유하게 했다. 거부 사유는 `Order.completePayment()`의 errorCode 세분화로 전달하고, 대사의 `handleOrderNotCompletable` 4분기를 해체했다. 도달 불가능한 PAID 성공-주체 dead 코드를 제거하고, 비중복 PAID는 환불 대신 통지+fail 안전망으로 두었다.

## 핵심 학습

### 1. "dead 코드"는 제거 전에 도달 불가를 증명하라 — 그리고 만약을 대비하라

ADR-048이 결정으로 박아둔 PAID 성공-주체 분기(`succeedApprovalRecordOnly`)가 정말 죽은 코드인지가 핵심 쟁점이었다. 근거는 코드와 DB 제약에 있었다 — 주문이 PAID가 되는 경로는 `succeedApproval`의 한 tx뿐이고, `uk_payment_approved_order_key`(주문당 SUCCEEDED APPROVE 1개)가 두 번째 결제를 `completePayment` 도달 전 `PAYMENT_DUPLICATE`로 막는다. 따라서 "주문 PAID인데 SUCCEEDED APPROVE 없음"은 모순이다. 다만 돈이 걸린 경로라 "증명됐으니 삭제"로 끝내지 않고, 만에 하나 그 상태에 도달하면 성공-주체를 오환불하지 않도록 환불 대신 통지+fail로 종착시켰다(금전 정합성은 희박한 경합도 안전하게).

### 2. 결합 제거 = 분기 이동이 아니라 분기 제거

`order.getStatus()` 분기를 facade로 옮기기만 하면 결합은 그대로다. 핵심은 주문 상태 해석을 주문 메서드(`completePayment`) 안에 가두고(Tell-Don't-Ask), 거부 결과만 errorCode로 전달해 결제가 주문 상태머신을 알 필요가 없게 만든 것이다. 주문 상태가 늘어도 facade 분기는 errorCode 단위로만 늘고, 결제가 주문을 재조회하는 결합은 사라진다.

### 3. 미래 구조는 "절반만" 깐다 — provider 중립 위치, resolver는 후속

multi-provider(카카오/토스)를 상상해 gateway resolver·공통 진입 UseCase를 미리 만들면 NaverPay 한 곳에만 맞는 틀린 추상이 나온다. 정규화 경계는 두 번째 provider의 실제 모양을 봐야 그어진다. 대신 facade를 provider 중립 위치(`payment.application`)에 둬서 "provider 공통 confirm" 절반만 미리 깔고, 나머지는 후속으로 명시했다(ADR-065, YAGNI).

### 4. 실행 전 독립 검토가 step 문서의 실행 불가 결함을 잡았다

step 문서를 별도 에이전트에게 검토시켜, 문서만 보고 구현하면 깨졌을 결함들을 사전에 잡았다 — (a) 변경이 깨뜨리는 기존 테스트(삭제할 서비스를 mock하던 `ReconcilePaymentUseCaseTest` 등)의 갱신 지시 누락, (b) `order==null` 경로가 실제로는 `completePayment` 이전 `ORDER_NOT_FOUND`로 나온다는 매핑 누락, (c) 회귀 테스트가 AC glob에 안 걸리는 사각. 설계가 옳아도 "지시서"가 실행 가능한지는 별개였다.

### 5. 자동 생성 코드도 코드베이스 컨벤션으로 다시 거른다

workflow가 `Outcome`을 sealed interface로 자동 도입했는데, 이 레포는 sealed interface 선례가 없고 "enum + nullable로 충분하면 그쪽" 컨벤션이었다. enum + record로 바꿨다. PR review의 `var` 제안도 마찬가지 — 방향(캐스팅 제거)은 맞지만 레포가 `var`를 0건 쓰고 `ErrorCode errorCode = ex.getErrorCode()` 선례가 있어 `var` 대신 명시 타입으로 변형 적용했다. "도구·리뷰어의 제안 방향"과 "이 레포의 표현 방식"을 분리해 판단했다.

## 함정 메모

- record로 바꾸면서 static 팩토리가 package-private이 됐다(interface일 땐 묵시적 public). 다른 패키지 테스트가 접근해 컴파일이 깨졌고 `public` 명시로 해결.
- 캐스팅(`(PaymentErrorCode) ex.getErrorCode()`)을 제거하니 `code`가 `ErrorCode` 타입이 되어 `Outcome.rejected(code)`(PaymentErrorCode 인자)가 타입 불일치로 깨졌다. if 조건이 보장하는 상수를 직접 넘기도록 동반 수정(동작 동일).

## 스코프 규율

- gateway resolver·공통 승인 진입 UseCase·PG 결과 정규화는 명시적으로 범위 밖(ADR-065). 두 번째 provider 도입 시 후속.
- API·DB 스키마 변경 없음. 순수 내부 구조 리팩터.
