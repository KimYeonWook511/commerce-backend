# Step 2: race-priority-by-payment-existence

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/payment-compensation-policy/prd.md`
- `/docs/tasks/payment-compensation-policy/architecture.md`
- `/docs/tasks/payment-compensation-policy/adr.md`
- `/docs/ADR.md` — ADR-011, ADR-012, ADR-013 참조
- `/docs/exception-strategy.md` — 보상 catch 2차 예외 처리 패턴
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `src/main/java/com/commerce/payment/domain/Payment.java`

## 작업

### 1. PaymentApprovalService에 isCompensationRequired 추가

`src/main/java/com/commerce/payment/application/PaymentApprovalService.java`에 아래 메서드를 추가한다:

```java
public boolean isCompensationRequired(String merchantPayKey) {
    return paymentRepository.findByMerchantPayKey(merchantPayKey).isEmpty();
}
```

- 기존 `findPaymentByMerchantPayKey`를 내부에서 재사용한다.
- `readOnly = true` 트랜잭션 안에서 실행된다 (클래스 레벨 설정 유지).

### 2. NaverPayApprovalService.failApproveAndCancelApprovedPayment에 Payment 체크 추가

`failApproveAttemptIfRequested` 호출 직후, `getOrCreateCancelAttempt` 호출 직전에 아래를 추가한다:

```java
if (!paymentApprovalService.isCompensationRequired(approveAttempt.getMerchantPayKey())) {
    log.warn(
        "Payment already completed, skipping PG cancel: merchantPayKey={}, paymentId={}",
        approveAttempt.getMerchantPayKey(),
        approveAttempt.getPaymentId()
    );
    return;
}
```

- `failApproveAttemptIfRequested` 호출 순서는 변경하지 마라. attempt mark는 Payment 체크와 무관하게 먼저 시도한다.
- 기존 `cancelAttempt.getStatus() != REQUESTED` 체크(cancel attempt 중복 방지)는 그대로 유지한다.

### 3. 테스트 추가

`src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`에 아래 케이스를 추가한다:

- Payment가 이미 존재할 때 `failApproveAndCancelApprovedPayment`가 cancel을 skip하는지 검증
- Payment가 없을 때 cancel이 정상 진행되는지 검증 (기존 동작 보존)

## 수정 가능 경로

- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - Payment 미존재 시 cancel 진행 (기존 동작) 테스트 통과
   - Payment 존재 시 cancel skip 테스트 통과
   - 기존 NaverPayApprovalServiceTest 전체 통과 (회귀 없음)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `completeVerifiedApproval`의 catch 분기 구조를 이 step에서 변경하지 마라. 이유: step 3에서 분리 처리하므로 두 변경을 혼재하면 각 step의 독립 검증이 불가능해진다.
- `isCompensationRequired` 내부에서 `paymentRepository.findByMerchantPayKey` 대신 새 쿼리를 만들지 마라. 이유: 기존 메서드 재사용으로 충분하고 중복 쿼리를 방지한다.
- `failApproveAttemptIfRequested` 호출을 제거하거나 이동하지 마라. 이유: ADR-013 패턴(보상 흐름의 의도 캡슐화 메서드 유지)을 보존해야 한다.
- 기존 테스트를 깨뜨리지 마라.
