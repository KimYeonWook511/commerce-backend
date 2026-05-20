# 태스크 아키텍처

## 개요

이 태스크는 보상 정책 코드를 NaverPay 어댑터(`payment.naverpay.application`)에서 결제 도메인 application 레이어(`payment.application`)로 이동한다. `PgCanceller` functional interface를 경계로 payment.application이 NaverPayCancelResult를 직접 의존하지 않도록 한다.

## 변경 대상

### 신설 (코드)
- `src/main/java/com/commerce/payment/application/port/PgCanceller.java`
- `src/main/java/com/commerce/payment/application/port/result/CancelOutcome.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`

### 수정 (코드)
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
  - 보상 메서드 4개 삭제: `compensateMerchantKeyMismatch`, `compensateAmountMismatch`, `compensateDuplicatePayment`, `compensateUnexpected`
  - 보상 골격 삭제: `failApproveAndCancelApprovedPayment`, `processCancelRequest`, `succeedCancel`, `markCancelFailed`
  - `private CancelOutcome pgCancel(PaymentAttempt, String)` 신설
  - catch 블록: `paymentApprovalCompensationService.compensateXxx(..., this::pgCancel)` 호출로 단순화

### 신설 (테스트)
- `src/test/java/com/commerce/payment/application/PaymentApprovalCompensationServiceTest.java`

### 수정 (테스트)
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`
  - 보상 관련 케이스: `naverPayGateway.cancel` 직접 검증 → `paymentApprovalCompensationService.compensateXxx` 호출 검증으로 변경
  - `pgCancel` 변환 케이스 독립 추가 (NaverPayCancelResult.Status별 CancelOutcome 변환)

## 설계 방향

### 레이어 의존 방향 (변경 전)
```
NaverPayApprovalService (naverpay.application)
  → PaymentApprovalAttemptService (payment.application)
  → PaymentCancellationAttemptService (payment.application)
  → PaymentApprovalService (payment.application)
  → [보상 정책 코드 내장]
  → NaverPayGateway (naverpay.application.port)
```

### 레이어 의존 방향 (변경 후)
```
NaverPayApprovalService (naverpay.application)
  → PaymentApprovalCompensationService (payment.application)  ← 보상 정책
  → NaverPayGateway (naverpay.application.port)
  → this::pgCancel (메서드 참조, NaverPayCancelResult → CancelOutcome 변환)

PaymentApprovalCompensationService (payment.application)
  → PaymentApprovalAttemptService
  → PaymentApprovalService
  → PaymentCancellationAttemptService
  → PgCanceller (port, @FunctionalInterface)  ← NaverPayApprovalService가 구현 주입
```

`payment.application`은 `NaverPayCancelResult`를 직접 import하지 않는다. PG-specific 응답은 `pgCancel` 메서드 내부에서 `CancelOutcome`으로 변환된다.

### PgCanceller 콜백 방식 선택 근거
- PaymentGateway port 완전 inversion(PG-agnostic approve/cancel 통합 port)은 PG가 둘 이상 될 때 자연 승격. 현 시점엔 over-engineering.
- Strategy 패턴은 PG가 하나뿐인 현 시점에 premature.
- 좁은 콜백(`PgCanceller`)으로 payment.application → NaverPay 직접 의존을 차단하면서 지금 필요한 최소 구조만 도입.

## 데이터 흐름

### 보상 흐름 (변경 후)

```
NaverPayApprovalService.completeVerifiedApproval (catch)
  → paymentApprovalCompensationService.compensateAmountMismatch(attempt, responseTotalAmount, this::pgCancel)
    → runPgCancel(attempt, AMOUNT_MISMATCH, failDetail, cancelAmount, cancelReason, pgCanceller)
      1. paymentApprovalAttemptService.failIfRequested(...)       [@Transactional REQUIRED]
      2. paymentApprovalService.isCompensationRequired(...)       [@Transactional REQUIRES_NEW]
         └─ false → log.warn + return
      3. paymentCancellationAttemptService.getOrCreate(...)       [@Transactional NOT_SUPPORTED]
      4. cancelAttempt.getStatus() != REQUESTED → return
      5. pgCanceller.cancel(cancelAttempt, cancelReason)          [NaverPayApprovalService.pgCancel]
         └─ naverPayGateway.cancel(...) → NaverPayCancelResult → CancelOutcome 변환
      6. outcome.status() 분기
         SUCCESS  → paymentCancellationAttemptService.succeed(...)  [@Transactional REQUIRED]
         PROCESSING → no-op
         FAILED   → paymentCancellationAttemptService.fail(...)     [@Transactional REQUIRED]
```

## 예외 및 실패 처리

- `pgCanceller.cancel` 중 예외 발생: `NaverPayApprovalService` 기존 catch처럼 `PaymentException`을 catch해 log.warn 후 swallow. 원래 승인 실패 예외를 가리지 않는다.
- `isCompensationRequired` false: log.warn 후 PG cancel 없이 return. Payment 이미 존재해 cancel 불필요한 경우.
- `cancelAttempt.getStatus() != REQUESTED`: cancel이 이미 진행됐거나 완료된 경우. return으로 skip.

## 테스트 포인트

- `PaymentApprovalCompensationServiceTest`:
  - dispatcher 4개 × (isCompensationRequired true/false) × (PG cancel outcome 3가지) 매트릭스
  - merchantKeyMismatch: PG cancel 없이 failIfRequested만 호출
  - `PgCanceller`를 Mockito stub으로 주입, 호출 여부 및 outcome별 분기 검증
- `NaverPayApprovalServiceTest`:
  - catch 블록: `paymentApprovalCompensationService.compensateXxx(...)` 호출 검증으로 갱신
  - `pgCancel`: NaverPayCancelResult.Status별 CancelOutcome 변환 독립 검증
