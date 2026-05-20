# Step 1: move-compensation-dispatcher

## 읽어야 할 파일

먼저 아래 파일들을 읽고 현재 코드 구조를 파악하라:

- `docs/tasks/payment-compensation-to-domain/prd.md`
- `docs/tasks/payment-compensation-to-domain/architecture.md`
- `docs/tasks/payment-compensation-to-domain/adr.md`
- `src/main/java/com/commerce/payment/application/port/PgCanceller.java` (step 0 신설)
- `src/main/java/com/commerce/payment/application/port/result/CancelOutcome.java` (step 0 신설)
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalAttemptService.java`
- `src/main/java/com/commerce/payment/application/PaymentCancellationAttemptService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/naverpay/application/port/result/NaverPayCancelResult.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`
- `docs/ADR.md` (ADR-013, ADR-014 확인)
- `docs/exception-strategy.md`

## 작업

### 신설: `PaymentApprovalCompensationService`

경로: `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`

- `package com.commerce.payment.application`
- `@Slf4j @Service @RequiredArgsConstructor`
- 클래스 레벨 `@Transactional` 없음 (ADR-T2)
- 의존성: `PaymentApprovalAttemptService`, `PaymentApprovalService`, `PaymentCancellationAttemptService`

**public 메서드 4개:**

```java
public void compensateMerchantKeyMismatch(PaymentAttempt approveAttempt) {
    // PG 결제 자체가 없으므로 cancel 없이 failIfRequested만.
    paymentApprovalAttemptService.failIfRequested(
        approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(), approveAttempt.getPaymentId(),
        PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치", LocalDateTime.now()
    );
}

public void compensateAmountMismatch(PaymentAttempt approveAttempt, int responseTotalAmount, PgCanceller pgCanceller) {
    runPgCancel(approveAttempt,
        PaymentAttemptFailCode.AMOUNT_MISMATCH,
        String.format("attemptAmount=%d, responseTotalAmount=%d", approveAttempt.getAmount(), responseTotalAmount),
        responseTotalAmount,
        "승인 금액 불일치",
        pgCanceller
    );
}

public void compensateDuplicatePayment(PaymentAttempt approveAttempt, Exception ex, PgCanceller pgCanceller) {
    runPgCancel(approveAttempt,
        PaymentAttemptFailCode.DUPLICATE_PAYMENT,
        Objects.toString(ex.getMessage(), "이미 완료된 결제 반영 시도"),
        approveAttempt.getAmount(),
        "이미 다른 결제가 완료된 주문으로 인한 취소",
        pgCanceller
    );
}

public void compensateUnexpected(PaymentAttempt approveAttempt, Exception ex, PaymentAttemptFailCode failCode, PgCanceller pgCanceller) {
    runPgCancel(approveAttempt,
        failCode,
        Objects.toString(ex.getMessage(), "예상치 못한 오류 발생"),
        approveAttempt.getAmount(),
        "결제 완료 반영 실패로 인한 취소",
        pgCanceller
    );
}
```

**private 메서드 `runPgCancel`:**

```java
private void runPgCancel(
    PaymentAttempt approveAttempt,
    PaymentAttemptFailCode failCode,
    String failDetail,
    int cancelAmount,
    String cancelReason,
    PgCanceller pgCanceller
) {
    // REQUESTED 상태가 아니면 mark를 skip한다. (race window에서 SUCCEEDED 상태로 도달해도 PG cancel은 그대로 진행)
    paymentApprovalAttemptService.failIfRequested(
        approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(), approveAttempt.getPaymentId(),
        failCode, failDetail, LocalDateTime.now()
    );

    if (!paymentApprovalService.isCompensationRequired(approveAttempt.getMerchantPayKey())) {
        log.warn(
            "Payment already completed, skipping PG cancel: merchantPayKey={}, paymentId={}",
            approveAttempt.getMerchantPayKey(), approveAttempt.getPaymentId()
        );
        return;
    }

    PaymentAttempt cancelAttempt = paymentCancellationAttemptService.getOrCreate(
        approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(),
        approveAttempt.getPaymentId(), cancelAmount
    );

    if (cancelAttempt.getStatus() != PaymentAttemptStatus.REQUESTED) {
        return;
    }

    try {
        CancelOutcome outcome = pgCanceller.cancel(cancelAttempt, cancelReason);
        switch (outcome.status()) {
            case SUCCESS -> paymentCancellationAttemptService.succeed(
                cancelAttempt.getMerchantPayKey(), cancelAttempt.getProvider(),
                cancelAttempt.getPaymentId(), LocalDateTime.now()
            );
            case PROCESSING -> {} // no-op
            case FAILED -> paymentCancellationAttemptService.fail(
                cancelAttempt.getMerchantPayKey(), cancelAttempt.getProvider(),
                cancelAttempt.getPaymentId(), outcome.failCode(), outcome.failDetail(), LocalDateTime.now()
            );
        }
    } catch (PaymentException ex) {
        log.warn(
            "Approved payment cancel failed: merchantPayKey={}, paymentId={}, cancelReason={}, errorCode={}",
            cancelAttempt.getMerchantPayKey(), cancelAttempt.getPaymentId(),
            cancelReason, ex.getErrorCode()
        );
    }
}
```

### 수정: `NaverPayApprovalService`

아래를 변경한다:

1. **의존성 추가**: `PaymentApprovalCompensationService paymentApprovalCompensationService` 필드 추가

2. **`pgCancel` private 메서드 신설**:
```java
private CancelOutcome pgCancel(PaymentAttempt cancelAttempt, String cancelReason) {
    NaverPayCancelResult result = naverPayGateway.cancel(
        cancelAttempt.getPaymentId(), cancelAttempt.getAmount(), cancelReason
    );
    return switch (result.getStatus()) {
        case SUCCESS, ALREADY_CANCELED -> CancelOutcome.success();
        case PROCESSING -> CancelOutcome.processing();
        case FAILED -> CancelOutcome.failed(result.getFailCode(), result.getFailDetail());
    };
}
```

3. **`completeVerifiedApproval` catch 블록 갱신**:
```java
catch (PaymentException ex) {
    log.error(
        "NaverPay approve complete failed by payment error: merchantPayKey={}, paymentId={}, responseMerchantPayKey={}, responseTotalAmount={}, errorCode={}",
        attempt.getMerchantPayKey(), attempt.getPaymentId(),
        responseMerchantPayKey, responseTotalAmount, ex.getErrorCode(), ex
    );
    switch ((PaymentErrorCode)ex.getErrorCode()) {
        case PAYMENT_MERCHANT_KEY_MISMATCH ->
            paymentApprovalCompensationService.compensateMerchantKeyMismatch(attempt);
        case PAYMENT_AMOUNT_MISMATCH ->
            paymentApprovalCompensationService.compensateAmountMismatch(attempt, responseTotalAmount, this::pgCancel);
        case PAYMENT_DUPLICATE ->
            paymentApprovalCompensationService.compensateDuplicatePayment(attempt, ex, this::pgCancel);
        default ->
            paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
    }
    throw ex;
}
catch (CustomException ex) {
    log.error(...);
    paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
    throw ex;
}
catch (Exception ex) {
    log.error(...);
    paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
    throw ex;
}
```

4. **삭제할 메서드**: `compensateMerchantKeyMismatch`, `compensateAmountMismatch`, `compensateDuplicatePayment`, `compensateUnexpected`, `failApproveAndCancelApprovedPayment`, `processCancelRequest`, `succeedCancel`, `markCancelFailed`, `failApprove`

5. **삭제할 import**: `NaverPayCancelResult`가 더 이상 필요 없으면 제거 확인 (`pgCancel` 메서드에서 사용하므로 유지)

### 신설 테스트: `PaymentApprovalCompensationServiceTest`

경로: `src/test/java/com/commerce/payment/application/PaymentApprovalCompensationServiceTest.java`

검증 매트릭스:

- `compensateMerchantKeyMismatch`: `failIfRequested` 호출 확인, `pgCanceller.cancel` 호출 안 함 확인
- `compensateAmountMismatch(isCompensationRequired=true, outcome=SUCCESS)`: cancel 성공 → `succeedCancel` 호출
- `compensateAmountMismatch(isCompensationRequired=true, outcome=PROCESSING)`: no-op
- `compensateAmountMismatch(isCompensationRequired=true, outcome=FAILED)`: `failCancel` 호출
- `compensateAmountMismatch(isCompensationRequired=false)`: `pgCanceller.cancel` 호출 안 함
- `compensateDuplicatePayment`, `compensateUnexpected`: 동일 매트릭스
- cancel attempt status != REQUESTED: `pgCanceller.cancel` 호출 안 함
- `pgCanceller.cancel` 중 `PaymentException` 발생: swallow, 원래 흐름 계속

`PgCanceller`를 `@Mock`으로 주입해 `given(pgCanceller.cancel(any(), any())).willReturn(CancelOutcome.success())` 형태로 stub.

### 수정 테스트: `NaverPayApprovalServiceTest`

- `@Mock PaymentApprovalCompensationService paymentApprovalCompensationService` 추가
- `@InjectMocks` 대상이 새 필드를 포함하도록 자동 주입
- 보상 검증 케이스 갱신: `naverPayGateway.cancel` 직접 검증 대신 `paymentApprovalCompensationService.compensateXxx(...)` 호출 검증으로 교체
- `pgCancel` 변환 독립 케이스 추가:
  - `NaverPayCancelResult.SUCCESS` → `CancelOutcome.status() == SUCCESS`
  - `NaverPayCancelResult.ALREADY_CANCELED` → `CancelOutcome.status() == SUCCESS`
  - `NaverPayCancelResult.PROCESSING` → `CancelOutcome.status() == PROCESSING`
  - `NaverPayCancelResult.FAILED` → `CancelOutcome.status() == FAILED` + failCode/failDetail 일치

`pgCancel`은 private이므로 `ReflectionTestUtils.invokeMethod` 또는 패키지-private으로 변경해 테스트 접근. 패키지-private으로 바꾸는 방법을 우선 사용한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행해 테스트가 모두 통과하는지 확인한다.
2. 아래를 확인한다:
   - `NaverPayApprovalService`에서 보상 메서드 8개(`compensate*`, `failApproveAndCancelApprovedPayment`, `processCancelRequest`, `succeedCancel`, `markCancelFailed`, `failApprove`)가 모두 삭제됐는가?
   - `payment.application` 코드가 `NaverPayCancelResult`를 import하지 않는가?
   - `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional`이 없는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional`을 붙이지 마라. 이유: `isCompensationRequired`의 `REQUIRES_NEW` 격리가 깨져 race 정책이 무너진다 (ADR-T2).
- `payment.application` 패키지에서 `NaverPayCancelResult`를 import하지 마라. 이유: 도메인 application이 PG 어댑터 결과 타입에 의존하면 레이어 의존 방향이 역전된다.
- `runPgCancel`을 public으로 노출하지 마라. 이유: dispatcher 4개를 통해서만 진입해야 한다.
