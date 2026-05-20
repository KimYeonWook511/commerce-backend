# Step 0: payment-attempt-domain-cleanup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/tasks/payment-attempt-service-split/prd.md`
- `docs/tasks/payment-attempt-service-split/architecture.md`
- `docs/tasks/payment-attempt-service-split/adr.md`
- `src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/test/java/com/commerce/payment/domain/PaymentAttemptTest.java`

루트 docs 추가:
- `docs/ADR.md` — ADR-012 (mark 메서드 선조건 검증 정책) 확인 필수

## 작업

### 1. `PaymentAttempt` 도메인 메서드 통합 (`src/main/java/com/commerce/payment/domain/PaymentAttempt.java`)

아래 변경을 적용한다:

**(a) mark 4개 → succeed/fail 2개로 통합**

```java
// 기존 markApproveSucceeded, markCancelSucceeded 대체
public void succeed(LocalDateTime respondedAt) {
    if (this.status != PaymentAttemptStatus.REQUESTED) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
    }
    this.status = PaymentAttemptStatus.SUCCEEDED;
    this.failCode = null;
    this.failDetail = null;
    this.respondedAt = respondedAt;
}

// 기존 markApproveFailed, markCancelFailed 대체
public void fail(PaymentAttemptFailCode failCode, String failDetail, LocalDateTime respondedAt) {
    if (this.status != PaymentAttemptStatus.REQUESTED) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
    }
    this.status = PaymentAttemptStatus.FAILED;
    this.failCode = failCode;
    this.failDetail = failDetail;
    this.respondedAt = respondedAt;
}
```

- type 가드(`if (this.type != PaymentAttemptType.APPROVE) throw ...`) 제거. 호출자(Service)가 올바른 type의 attempt를 가져오므로 도메인에서 중복 검증할 이유 없음
- `status != REQUESTED` 가드는 유지 (ADR-012 정책 보존)
- 기존 메서드 4개 삭제: `markApproveSucceeded`, `markApproveFailed`, `markCancelSucceeded`, `markCancelFailed`
- `PaymentAttemptType` 필드와 enum은 그대로 유지

**(b) `verifyApprovedResponse` 신설**

```java
public void verifyApprovedResponse(String responseMerchantPayKey, int responseTotalAmount) {
    if (!this.merchantPayKey.equals(responseMerchantPayKey)) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
    }
    if (this.amount != responseTotalAmount) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }
}
```

- `NaverPayApprovalService.validateApprovedMerchantPayKeyOrThrow`/`validateApprovedAmountOrThrow`의 로직과 동등
- log.warn은 NaverPayApprovalService의 private 메서드에 있었으나 도메인 메서드는 순수 검증만 담당. log는 NaverPay 쪽에서 처리하거나 생략

### 2. `PaymentAttemptService` 임시 갱신 (`src/main/java/com/commerce/payment/application/PaymentAttemptService.java`)

step 1에서 Service를 분리하기 전까지 기존 Service가 새 도메인 메서드를 호출하도록 갱신한다:
- `attempt.markApproveSucceeded(...)` → `attempt.succeed(...)`
- `attempt.markApproveFailed(...)` → `attempt.fail(...)`
- `attempt.markCancelSucceeded(...)` → `attempt.succeed(...)`
- `attempt.markCancelFailed(...)` → `attempt.fail(...)`

### 3. `NaverPayApprovalService` 검증 메서드 교체 (`src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`)

`completeVerifiedApproval` 메서드의 try 블록 첫 두 줄 교체:

```java
// 기존
validateApprovedMerchantPayKeyOrThrow(attempt, responseMerchantPayKey);
validateApprovedAmountOrThrow(attempt, responseTotalAmount);

// 변경 후
attempt.verifyApprovedResponse(responseMerchantPayKey, responseTotalAmount);
```

- `validateApprovedMerchantPayKeyOrThrow`, `validateApprovedAmountOrThrow` private 메서드 삭제
- 기존 두 메서드의 log.warn은 삭제 (도메인 메서드는 순수 검증만 담당). 기존 warn 정보는 caller의 catch 블록에서 이미 error 로그가 처리됨

### 4. 테스트 갱신

**(a) `PaymentAttemptTest` (`src/test/java/com/commerce/payment/domain/PaymentAttemptTest.java`)**

- 기존 케이스 갱신: `markApproveSucceeded` → `succeed`, `markApproveFailed` → `fail` 등 메서드명/시그니처 변경
- type 가드 관련 케이스 4개 삭제:
  - `markApproveSucceeded_whenTypeIsCancel_throwException`
  - `markApproveFailed_whenTypeIsCancel_throwException`
  - `markCancelSucceeded_whenTypeIsApprove_throwException`
  - `markCancelFailed_whenTypeIsApprove_throwException`
- `verifyApprovedResponse` 케이스 추가 (총 3개):
  - `verifyApprovedResponse_whenMerchantPayKeyMismatch_throwPaymentMerchantKeyMismatch`
  - `verifyApprovedResponse_whenAmountMismatch_throwPaymentAmountMismatch`
  - `verifyApprovedResponse_whenBothMatch_noException`

**(b) `PaymentAttemptServiceTest` (`src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java`)**

- mark* 호출을 succeed/fail로 갱신. 메서드 자체는 step 1에서 Service 분리 전까지 유지

**(c) `NaverPayApprovalServiceTest` (`src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`)**

- `validateApprovedMerchantPayKeyOrThrow`, `validateApprovedAmountOrThrow` 관련 케이스에서 `attempt.verifyApprovedResponse` 호출 검증으로 교체
- 단, 이 케이스들은 NaverPay 단위 테스트이므로 `attempt.verifyApprovedResponse` 를 mock하거나 실제 도메인 객체를 사용하는 방식으로 정리

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria를 실행한다.
2. 아래를 확인한다:
   - `PaymentAttempt`에 `markApprove*`/`markCancel*` 메서드가 없고 `succeed`/`fail`/`verifyApprovedResponse`만 있는가?
   - `NaverPayApprovalService`에 `validateApproved*` private 메서드가 없는가?
   - `PAYMENT_ATTEMPT_TYPE_MISMATCH` 관련 테스트가 삭제됐는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `PaymentAttemptService`를 이 step에서 삭제하지 마라. 이유: step 1에서 Service를 분리하기 전까지 기존 Service가 호출처 역할을 유지해야 컴파일 오류 없음
- `NaverPayApprovalService`의 보상 메서드(`compensate*`, `failApproveAndCancelApprovedPayment`)를 건드리지 마라. 이유: task B 범위
- type 가드만 제거하고 status 가드(`status != REQUESTED`)는 반드시 유지하라. 이유: ADR-012 정책 보존
- 기존 테스트를 깨뜨리지 마라
