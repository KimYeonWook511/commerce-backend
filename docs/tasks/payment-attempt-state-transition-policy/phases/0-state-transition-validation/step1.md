# Step 2: domain-validation

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라.

- `/docs/tasks/payment-attempt-state-transition-policy/prd.md`
- `/docs/tasks/payment-attempt-state-transition-policy/architecture.md`
- `/docs/tasks/payment-attempt-state-transition-policy/adr.md`
- `/docs/tasks/payment-attempt-state-transition-policy/api-spec.md`

구현 대상 코드:

- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `/src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `/src/test/java/com/commerce/payment/domain/PaymentAttemptTest.java`

일관성 참고용:

- `/src/main/java/com/commerce/order/domain/Order.java` (cancel, completePayment 선조건 검증 패턴)
- `/src/main/java/com/commerce/payment/application/PaymentAttemptService.java` (mark 호출 흐름 확인)

## 작업

### 1. `PaymentErrorCode.java` — 신규 에러 코드 2개 추가

기존 에러 코드 목록에 추가한다. 두 코드 모두 HTTP 500 INTERNAL_SERVER_ERROR를 사용한다.

```java
PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-500-1",
    "결제 시도 상태 전이가 허용되지 않습니다"),
PAYMENT_ATTEMPT_TYPE_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-500-2",
    "결제 시도 타입과 mark 요청이 일치하지 않습니다");
```

### 2. `PaymentAttempt.java` — 4개 mark 메서드에 type + status 검증 추가

모든 mark 메서드에 동일한 패턴을 적용한다. type 검증을 status 검증보다 먼저 배치한다.

```java
public void markApproveSucceeded(LocalDateTime respondedAt) {
    if (this.type != PaymentAttemptType.APPROVE) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_TYPE_MISMATCH);
    }
    if (this.status != PaymentAttemptStatus.REQUESTED) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
    }
    this.status = PaymentAttemptStatus.SUCCEEDED;
    this.failCode = null;
    this.failDetail = null;
    this.respondedAt = respondedAt;
}
```

- `markApproveFailed`: `type == APPROVE` 검증 + `status == REQUESTED` 검증
- `markCancelSucceeded`: `type == CANCEL` 검증 + `status == REQUESTED` 검증
- `markCancelFailed`: `type == CANCEL` 검증 + `status == REQUESTED` 검증

### 3. `NaverPayApprovalService.java` — `failApproveAndCancelApprovedPayment` 내 `failApprove` try-catch 보호

`failApproveAndCancelApprovedPayment` 함수 내에서 `failApprove` 호출 한 곳만 try-catch로 감싼다. **return을 넣지 않는다** — PG cancel은 mark 실패 여부와 무관하게 무조건 진행해야 한다.

```java
try {
    failApprove(approveAttempt, failCode, failDetail);
} catch (PaymentException markEx) {
    log.warn(
        "Approve attempt mark failed during compensation, proceeding to PG cancel: merchantPayKey={}, paymentId={}, errorCode={}",
        approveAttempt.getMerchantPayKey(),
        approveAttempt.getPaymentId(),
        markEx.getErrorCode(),
        markEx
    );
    // return 없음 — PG cancel은 무조건 시도 (외부 정합성 보존)
}
```

**다른 catch 블록은 변경하지 않는다.** 라인 130, 145, 149 catch 블록은 이번 step에서 건드리지 않는다.

### 4. `PaymentAttemptTest.java` — 전이/type 위반 테스트 케이스 9개 추가

기존 테스트 클래스에 추가한다. SUCCEEDED/FAILED 상태 setup은 `createApproveRequested` 또는 `createCancelRequested` 후 첫 번째 mark 호출로 상태를 이동시킨 뒤, 두 번째 mark 호출로 throw를 검증한다 (ReflectionTestUtils 불필요).

추가할 테스트 목록:

```
markApproveSucceeded_whenStatusSucceeded_throwException
markApproveSucceeded_whenStatusFailed_throwException
markApproveSucceeded_whenTypeIsCancel_throwException
markApproveFailed_whenStatusNotRequested_throwException
markApproveFailed_whenTypeIsCancel_throwException
markCancelSucceeded_whenStatusNotRequested_throwException
markCancelSucceeded_whenTypeIsApprove_throwException
markCancelFailed_whenStatusNotRequested_throwException
markCancelFailed_whenTypeIsApprove_throwException
```

각 케이스는 `PaymentException`과 정확한 `PaymentErrorCode`를 검증한다. 기존 `PaymentAttemptTest`의 `satisfies` + `assertThat(orderException.getErrorCode())` 패턴을 참고한다.

## Acceptance Criteria

```bash
./gradlew test
```

PaymentErrorCode enum 변경이 포함되므로 전체 테스트를 실행한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 신규 테스트 9개가 모두 통과하는가?
   - 기존 `PaymentAttemptTest` 케이스가 깨지지 않는가?
   - `PaymentAttemptServiceTest`, `NaverPayApprovalServiceTest` 회귀가 없는가?
3. mark 메서드 사용처를 탐색해 신규 검증에 걸리는 경로가 없는지 확인한다.
   ```bash
   rg "markApproveSucceeded\|markApproveFailed\|markCancelSucceeded\|markCancelFailed" src/main/java src/test/java
   ```

## 금지사항

- `NaverPayApprovalService`의 라인 130 (`catch (PaymentException ex)`), 라인 145 (`catch (CustomException ex)`), 라인 149 (`catch (Exception ex)`) catch 블록에 log.error를 추가하지 마라. 이유: 해당 작업은 후속 Issue #111에서 처리한다.
- `failApproveAndCancelApprovedPayment` 함수 내 `failApprove` try-catch에 `return`을 넣지 마라. 이유: PG cancel은 mark 실패 여부와 무관하게 무조건 진행해야 한다.
- `PaymentAttemptStatus` enum을 변경하지 마라. 이유: 이번 작업 범위 외.
- `processApproveAttempt` switch 분기를 변경하지 마라. 이유: 이미 application 레벨 안전망으로 동작 중이며 변경 불필요.
- 기존 테스트를 깨뜨리지 마라.
