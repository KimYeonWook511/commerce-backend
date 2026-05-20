# Step 2: split-compensation-by-failure-meaning

## 읽어야 할 파일

먼저 아래 파일들을 읽고 현재 구조를 파악하라:

- `/docs/tasks/payment-compensation-policy/prd.md`
- `/docs/tasks/payment-compensation-policy/architecture.md`
- `/docs/tasks/payment-compensation-policy/adr.md`
- `/docs/exception-strategy.md` — ADR-013 보상 catch 2차 예외 처리 패턴
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` — step 1에서 변경된 파일

## 작업

### 1. 의미별 보상 메서드 4개 추가

`NaverPayApprovalService`에 아래 private 메서드를 추가한다:

```java
private void compensateMerchantKeyMismatch(PaymentAttempt attempt) {
    // 우리 시스템 키 오류이므로 PG 결제 자체가 없다. cancel 없이 failApprove만.
    failApprove(attempt, PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치");
}

private void compensateAmountMismatch(PaymentAttempt attempt, int responseTotalAmount) {
    failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.AMOUNT_MISMATCH,
        String.format("attemptAmount=%d, responseTotalAmount=%d", attempt.getAmount(), responseTotalAmount),
        responseTotalAmount, "승인 금액 불일치");
}

private void compensateDuplicatePayment(PaymentAttempt attempt, Exception ex) {
    failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.DUPLICATE_PAYMENT,
        ex.getMessage(), attempt.getAmount(), "이미 다른 결제가 완료된 주문으로 인한 취소");
}

private void compensateUnexpected(PaymentAttempt attempt, Exception ex,
        PaymentAttemptFailCode failCode, String cancelReason) {
    failApproveAndCancelApprovedPayment(attempt, failCode,
        ex.getMessage(), attempt.getAmount(), cancelReason);
}
```

### 2. completeVerifiedApproval의 catch 분기 교체

기존 catch 블록을 아래처럼 교체한다. log.error는 기존 위치 그대로 유지한다:

```java
} catch (PaymentException ex) {
    log.error("NaverPay approve complete failed by payment error: ...", ex);
    switch ((PaymentErrorCode)ex.getErrorCode()) {
        case PAYMENT_MERCHANT_KEY_MISMATCH -> compensateMerchantKeyMismatch(attempt);
        case PAYMENT_AMOUNT_MISMATCH ->
            compensateAmountMismatch(attempt, responseTotalAmount);
        case PAYMENT_DUPLICATE ->
            compensateDuplicatePayment(attempt, ex);
        default -> compensateUnexpected(attempt, ex,
            PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, "결제 완료 반영 실패로 인한 취소");
    }
    throw ex;
} catch (CustomException ex) {
    log.error("NaverPay approve complete failed by custom error: ...", ex);
    compensateUnexpected(attempt, ex,
        PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, "결제 완료 반영 실패로 인한 취소");
    throw ex;
} catch (Exception ex) {
    log.error("NaverPay approve complete failed by unexpected error: ...", ex);
    compensateUnexpected(attempt, ex,
        PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, "결제 완료 반영 중 예상치 못한 오류");
    throw ex;
}
```

- `responseTotalAmount`는 `completeVerifiedApproval` 파라미터에서 전달된다.
- `log.error` 메시지와 파라미터(merchantPayKey, paymentId, errorCode)는 기존 내용 그대로 유지한다.

## 수정 가능 경로

- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - MERCHANT_KEY_MISMATCH 시 cancel이 호출되지 않음
   - AMOUNT_MISMATCH 시 cancel이 호출됨
   - PAYMENT_DUPLICATE 시 cancel이 호출됨
   - 기존 NaverPayApprovalServiceTest 전체 통과
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `failApproveAndCancelApprovedPayment` 내부 로직을 이 step에서 변경하지 마라. 이유: step 1에서 이미 Payment 체크 로직이 추가됐으며, catch 분기 분리와는 관심사가 다르다.
- Strategy 패턴이나 다른 추상화를 도입하지 마라. 이유: PG가 NaverPay 하나뿐인 현 시점에서 over-design이다.
- 기존 `log.error` 메시지 내용과 위치를 변경하지 마라. 이유: 운영 모니터링 알람 패턴이 이 메시지에 의존할 수 있다.
- 기존 테스트를 깨뜨리지 마라.
