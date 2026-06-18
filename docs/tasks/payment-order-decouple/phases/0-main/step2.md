# step2 — realtime-approval-delegate

## 목표

실시간 NaverPay 승인 진입점(`ApproveNaverPayUseCase`)이 step1에서 만든 confirm facade를 공유하도록
위임한다. PG 프로토콜·검증·사전 가드는 진입점에 남기고(provider 특화), "승인 사실 확정 + 거부 보상"만
facade로 옮겨 두 진입점을 일원화한다. 실시간 사용자 대면 에러 응답 동작은 보존한다(회귀 금지).

## 배경·맥락 (중요)

- 현재 `ApproveNaverPayUseCase.completeVerifiedApproval()`(약 169~218행)은 `succeedApproval`을 직접
  호출하고, 거부 시 catch 블록에서 `PaymentException`의 errorCode를 switch로 분기해 보상을 고른 뒤
  (`compensateMerchantKeyMismatch`/`compensateAmountMismatch`/`compensateDuplicatePayment`)
  **예외를 다시 던져** 사용자에게 에러로 응답한다.
- step1에서 facade(`ConfirmApprovalUseCase`)가 이 "확정 시도 + 거부 보상" 코어를 이미 소유한다. 이
  step은 실시간 진입점이 그 facade를 호출하도록 바꾼다.
- 실시간은 거부를 `FAILED` 종착으로 끝내는 대사와 달리, 거부 사유를 사용자에게 **에러 응답**으로
  돌려줘야 한다(중복·금액 불일치 등). 그래서 facade 반환 `Outcome`을 해당 `PaymentException`으로
  다시 번역하는 매핑이 이 step의 실질적인 일이다.
- 검증(`verifyApprovedResponse`)의 입력은 PG approve 응답에서 오므로 진입점에 남긴다.

## 구현 지시

### 1) completeVerifiedApproval을 facade 위임으로 교체

- `succeedApproval` 직접 호출 + catch switch 보상 블록을 step1 facade 호출로 교체한다.
- `payment.verifyApprovedResponse(...)` 검증은 진입점에 유지한다(입력 소스가 PG approve 응답).
- 보상에 필요한 입력(응답 금액 등)과 PG 취소 콜백(`this::pgCancel`)을 facade에 전달한다.

### 2) Outcome → 사용자 응답/예외 번역

- facade 반환 `Outcome`을 번역한다: 확정 성공 → `toResponse(...)`(기존 성공 응답), 거부 →
  기존과 동일한 `PaymentException`(중복→`PAYMENT_DUPLICATE`, 금액 불일치→해당 코드 등)으로 throw해
  **사용자 대면 에러 응답을 보존**한다.
- 보상 비대상(정상 승인 후 기록 실패 등 옛 `default → {}` 케이스)은 보상 없이 예외를 전파한다 —
  approve가 REQUESTED로 남아 대사가 self-heal하는 기존 동작을 보존한다.
- 기존에 거부 시 사용자가 받던 errorCode 응답이 바뀌지 않아야 한다.

## 하지 마라

- PG 프로토콜 흐름(`processApproveRequest`/`processAlreadyComplete` 등 approve·history 호출과 PG 상태
  해석)을 facade로 옮기지 마라. 이유: provider 특화 로직이다. facade는 provider 중립 확정만 담는다.
- 사전 가드(`existsUnknownByOrderId`/`existsApprovedByOrderId`)와 진입 흐름(`processApprovePayment`의
  상태 분기)을 바꾸지 마라. 이유: 실시간 진입 차단 로직이며 이번 위임 대상이 아니다.
- 실시간 거부 시 사용자 에러 응답 동작을 바꾸지 마라. 이유: 사용자 대면 계약 회귀를 만들지 않는다.
- gateway resolver나 공통 승인 진입 UseCase를 만들지 마라. 이유: provider가 NaverPay 하나뿐이다.
  두 번째 provider 도입 시 후속이다(ADR-L4, YAGNI).

## 관련 파일

- `src/main/java/com/commerce/payment/naverpay/application/usecase/ApproveNaverPayUseCase.java` (`completeVerifiedApproval` 169~218)
- `src/main/java/com/commerce/payment/application/usecase/ConfirmApprovalUseCase.java` (step1에서 신설)
- `src/main/java/com/commerce/payment/application/usecase/CompensateApprovalUseCase.java`
- `docs/tasks/payment-order-decouple/adr.md` (ADR-L1·L4)

## Acceptance Criteria

```bash
./gradlew compileJava
./gradlew test --tests "*NaverPay*"
./gradlew test --tests "*Approve*"
./gradlew test --tests "*Reconcil*"
```
