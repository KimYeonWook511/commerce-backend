# step1 — approval-orchestration-facade

## 목표

결제 승인 확정 흐름에서 결제→주문 단방향 결합을 만들고, 대사(`ReconcilePaymentUseCase`)가
`order.getStatus()`로 주문 상태머신을 대신 분기하던 `handleOrderNotCompletable`을 해체한다.
provider 중립 confirm facade를 신설해 "승인 사실 확정 + 거부 보상"을 단일 조율로 모은다.
대사 경로(scheduler 진입)를 이 facade로 위임한다. 실시간 진입점(NaverPay)은 step2에서 다룬다.

이 step만으로 대사 경로 전체가 컴파일·테스트를 통과해야 한다(중간 상태에서 깨지지 않음).

## 배경·맥락 (중요)

- 현재 `ReconcilePaymentUseCase.executeApprove()`(약 430~464행)는 `succeedApproval` 거부 시
  `OrderException(ORDER_PAID_NOT_ALLOWED)` 또는 `PaymentException(PAYMENT_DUPLICATE)`를 받아
  `handleOrderNotCompletable()`(약 467~522행)로 들어간다. 거기서 **주문을 재조회하고
  `order.getStatus()`로 4분기**한다: `CANCELED`→보상 환불, `PAID`→중복/성공-주체 판별,
  `null`→통지+fail, 그 외 비-INIT→fail.
- `order.getStatus()` 주문 상태 분기는 `handleOrderNotCompletable` 한 곳에만 존재한다(484·493행).
  결제가 주문 상태머신을 대신 도는 결합이다.
- PAID 성공-주체 분기(`succeedApprovalRecordOnly`, 508행)는 `uk_payment_approved_order_key`
  (주문당 SUCCEEDED APPROVE 1개) 제약상 도달 불가능한 dead 코드다 — 주문이 PAID가 되는 유일한
  경로(`succeedApproval`의 한 tx)가 SUCCEEDED 결제를 함께 남기므로 `existsApprovedByOrderId`는 항상
  true다(ADR-L3).
- 보상 동작 자체(`CompensateApprovalUseCase`의 `compensateCanceledOrderApproval`/
  `compensateDuplicatePayment`/`compensateMerchantKeyMismatch`/`compensateAmountMismatch`)와
  `order==null` 통지·tx 단위작업(`SucceedPaymentApprovalService`)은 그대로 재사용한다.

## 구현 지시

### 1) Order.completePayment 거부 사유 세분화 (ADR-L2)

- `OrderErrorCode`에 거부 사유 코드를 추가한다(기존 네이밍 컨벤션을 따름): 주문이 이미 PAID,
  주문이 CANCELED, 그 외 비-INIT 상태. 예: `ORDER_ALREADY_PAID`, `ORDER_CANCELED_FOR_PAYMENT`,
  `ORDER_INVALID_STATE_FOR_PAYMENT`.
- `Order.completePayment()`를 상태별로 분기해 던지게 한다: `INIT`→`PAID` 전이(성공),
  `PAID`→`ORDER_ALREADY_PAID`, `CANCELED`→`ORDER_CANCELED_FOR_PAYMENT`,
  그 외→`ORDER_INVALID_STATE_FOR_PAYMENT`. 성공 동작(INIT→PAID)은 그대로다.
- 기존 `ORDER_PAID_NOT_ALLOWED`는 아래 §5의 테스트 갱신까지 마친 뒤, production·test를 통틀어 사용처가
  없으면 제거하고 있으면 보존한다(테스트 갱신 전에는 사용처가 남아 있으므로 제거 여부를 판단하지 않는다).

### 2) provider 중립 confirm facade 신설 (ADR-L1)

- `payment/application/usecase/`에 조율 UseCase를 신설한다(가칭 `ConfirmApprovalUseCase`).
  `@Component`, **tx를 열지 않는다**(ADR-054/055). naverpay 패키지에 두지 마라.
- 책임: 검증된 APPROVE `Payment`를 받아 `succeedApproval` 시도 → 성공이면 확정 결과, 거부면 사유별
  보상 후 거부 결과를 반환한다.
- 거부 매핑(errorCode 기반, **주문 재조회 없음**):
  - `ORDER_CANCELED_FOR_PAYMENT` → `compensateCanceledOrderApproval` (보상 환불)
  - `ORDER_ALREADY_PAID` / `PAYMENT_DUPLICATE` → `existsApprovedByOrderId`로 판별:
    true(중복)면 `compensateDuplicatePayment`(보상 환불), **false(비중복)면 환불하지 않고 정합성 통지
    + FAILED 종착**(ADR-L3, 기존 `order==null` 통지 안전망과 동형).
  - `PAYMENT_MERCHANT_KEY_MISMATCH` → `compensateMerchantKeyMismatch`
  - `PAYMENT_AMOUNT_MISMATCH` → `compensateAmountMismatch`
  - `ORDER_NOT_FOUND` → 환불 없이 정합성 통지 + FAILED 종착. 주문 자체가 없는 정합성 오류로,
    `completePayment` 이전 `SucceedPaymentApprovalService.findByIdForUpdate`에서 던져진다. 옛 코드의
    `order==null` 처리(별도 `findById` 재조회)를 facade가 이 errorCode 수신으로 대체한다(ADR-049 보존).
  - `ORDER_INVALID_STATE_FOR_PAYMENT`(그 외 비-INIT) → 환불 없이 FAILED 종착.
- 보상에 필요한 입력(취소 금액 등)과 PG 취소 콜백(`PgCanceller`)은 진입점마다 소스가 다르므로
  (실시간=PG approve 응답, 대사=PG history) facade가 파라미터/콜백으로 받는다.
- facade는 결과를 **`Outcome`(반환 타입)**으로 돌려준다. 진입점이 응답/종착으로 번역할 수 있어야 한다:
  - 거부 사유별 **원래 `PaymentErrorCode`를 보존**한다 — step2의 실시간 진입점이 거부를 동일 errorCode의
    `PaymentException`으로 다시 throw해 사용자 대면 응답을 보존해야 하기 때문이다.
  - **보상 비대상**(위 매핑에 없는 `PaymentException` — 예: 정상 승인 후 기록 실패)은 보상 없이 예외를
    전파하는 경로를 Outcome 모델에 포함한다(옛 실시간 `default → {}` 동작 보존).
  - 정확한 enum 구성은 구현에 맡기되 위 매핑 결과·전파 케이스를 구분할 수 있어야 한다.

### 3) 대사 위임 + handleOrderNotCompletable 해체

- `ReconcilePaymentUseCase.executeApprove()`가 `succeedApproval` 직접 호출 + 거부 catch 분기 대신
  facade를 호출하도록 교체한다. facade 반환 `Outcome`을 `PaymentReconcileOutcome`
  (SUCCEEDED/FAILED)으로 번역한다.
- `handleOrderNotCompletable()`을 삭제한다. `order.getStatus()` 분기가 사라진다.
- ADR-043(취소 주문 보상 환불)·ADR-049(order==null 통지) 동작은 facade가 보존한다.

### 4) dead 코드 제거 (ADR-L3)

- `SucceedPaymentApprovalRecordService` / `succeedApprovalRecordOnly`를 삭제한다(facade의 비중복 PAID
  통지+fail 안전망이 대체). 다른 사용처가 없는지 `grep`으로 확인한 뒤 제거한다.

### 5) 기존 테스트 갱신 (필수)

이 step의 변경은 기존 테스트를 깨뜨린다. 같은 step에서 함께 갱신/삭제한다:

- `ReconcilePaymentUseCaseTest` — 삭제 대상 `SucceedPaymentApprovalRecordService`의 import·mock·검증을
  제거한다(안 하면 컴파일이 깨진다). `order==null` / `CANCELED` / `PAID` 케이스를 옛 `findById` 재조회
  모델이 아니라 facade 위임 + errorCode 매핑 기준으로 재작성한다. 특히 **PAID 비중복(성공-주체) 케이스는
  환불이 아니라 통지+fail을 검증**하도록 바꾼다(ADR-L3).
- `OrderTest` — `completePayment` 거부 기대를 새 errorCode(`ORDER_ALREADY_PAID` /
  `ORDER_CANCELED_FOR_PAYMENT` / `ORDER_INVALID_STATE_FOR_PAYMENT`)로 갱신한다.
- `PaymentApprovalServiceConcurrencyTest` — 같은 주문 동시 승인의 진 쪽이 던지는 경합 허용 예외
  `ORDER_PAID_NOT_ALLOWED`를 `ORDER_ALREADY_PAID`로 갱신한다.
- `SucceedPaymentApprovalRecordServiceTest` — 서비스 삭제에 맞춰 파일을 삭제한다(존재 시).

## 하지 마라

- 실시간 승인(`ApproveNaverPayUseCase`)을 이 step에서 바꾸지 마라. 이유: 실시간 통합은 step2다. 한
  step에 진입점 둘을 동시에 바꾸면 회귀 범위가 커진다.
- `order.getStatus()` 분기를 facade로 옮기기만 하지 마라. 이유: 목적은 분기 이동이 아니라 제거다.
  거부 사유는 errorCode로 받아 분기한다.
- 비중복 PAID(`existsApprovedByOrderId == false`)를 환불하지 마라. 이유: 도달 불가지만 만약 도달하면
  성공-주체일 수 있어 정당한 결제를 오환불한다(금전 정합성은 희박한 경합도 안전하게). 통지+fail로
  종착시킨다.
- 새 `PaymentStatus`를 만들지 마라. 이유: ADR-039/044 — status는 사실만 담는다.
- 새 PG 호출 로직이나 새 보상 서비스를 만들지 마라. 이유: 기존 `CompensateApprovalUseCase`·
  `SucceedPaymentApprovalService`·`PgCanceller`를 재사용한다.

## 관련 파일

- `src/main/java/com/commerce/order/domain/Order.java` (`completePayment`)
- `src/main/java/com/commerce/order/domain/exception/OrderErrorCode.java`
- `src/main/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCase.java` (`executeApprove` 430~464, `handleOrderNotCompletable` 467~522)
- `src/main/java/com/commerce/payment/application/service/SucceedPaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/service/SucceedPaymentApprovalRecordService.java` (제거 대상)
- `src/main/java/com/commerce/payment/application/usecase/CompensateApprovalUseCase.java` (보상 메서드 재사용)
- `src/main/java/com/commerce/payment/application/port/NotificationPort.java` (비중복 PAID 통지)
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`existsApprovedByOrderId`)
- `src/test/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCaseTest.java` (재작성)
- `src/test/java/com/commerce/order/domain/OrderTest.java` (errorCode 기대 갱신)
- `src/test/java/com/commerce/payment/application/service/concurrency/PaymentApprovalServiceConcurrencyTest.java` (경합 예외 갱신)
- `src/test/java/com/commerce/payment/application/service/SucceedPaymentApprovalRecordServiceTest.java` (삭제, 존재 시)
- `docs/tasks/payment-order-decouple/adr.md` (ADR-L1·L2·L3)

## Acceptance Criteria

```bash
./gradlew compileJava
./gradlew test --tests "*Reconcil*"
./gradlew test --tests "*OrderTest*"
./gradlew test --tests "*Concurrency*"
# dead 코드가 제거됐어야 한다 (파일 없으면 ! -f 가 0)
test ! -f src/main/java/com/commerce/payment/application/service/SucceedPaymentApprovalRecordService.java
```
