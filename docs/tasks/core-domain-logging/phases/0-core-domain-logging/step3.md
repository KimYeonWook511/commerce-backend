# Step 3: payment-domain-logging

## 읽어야 할 파일

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/logging-conventions.md`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java` — 신규/멱등 분기 구조
- `src/main/java/com/commerce/payment/application/PaymentReadyService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalAttemptService.java` — 이미 `@Slf4j` 적용, 메시지 톤 참고
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` — 이미 `@Slf4j` 적용, 보상 catch 패턴 참고

## 작업

### 1. `PaymentReadyService` — 결제 준비 완료 INFO

파일: `src/main/java/com/commerce/payment/application/PaymentReadyService.java`

- 클래스 상단에 `@Slf4j` 부착
- `readyPayment(PaymentReadyCommand command)`의 `return PaymentReadyResult.builder()...build()` 직전:
  ```java
  log.info("결제 준비 완료 merchantPayKey={} orderId={} memberId={} amount={}",
      order.getMerchantPayKey(), order.getId(), command.getMemberId(), totalPayAmount);
  ```

### 2. `PaymentApprovalService` — 신규 완료 + 멱등 흡수 분리 INFO

파일: `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`

- 클래스 상단에 `@Slf4j` 부착
- `completeApprovedPayment(...)`의 분기 처리:
  - 멱등 흡수 분기 (`if (completedPayment != null) return completedPayment` 전):
    ```java
    log.info("결제 승인 멱등 흡수 merchantPayKey={} pgPaymentId={}", merchantPayKey, pgPaymentId);
    return completedPayment;
    ```
  - 신규 완료 분기 (`paymentRepository.save(Payment.createCompleted(...))` 결과를 반환하기 전 또는 직후):
    ```java
    Payment savedPayment = paymentRepository.save(
        Payment.createCompleted(order, provider, merchantPayKey, pgPaymentId, approvedAt)
    );
    log.info("결제 승인 완료 merchantPayKey={} provider={} pgPaymentId={} orderId={}",
        merchantPayKey, provider, pgPaymentId, order.getId());
    return savedPayment;
    ```
  - `findPaymentByMerchantPayKey`, `isCompensationRequired`는 조회 메서드 — INFO 추가하지 않음

## 수정 가능 경로

- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/PaymentReadyService.java`
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드 실행 → 기존 테스트 모두 PASS
2. 2개 파일에 `@Slf4j` 부착 확인
3. INFO 로그 메시지가 사전 시그니처와 정확히 일치
4. `PaymentApprovalService.completeApprovedPayment()` 멱등 흡수와 신규 완료가 별개 메시지로 분리되었는지 확인
5. `PaymentReadyService`의 `amount` 필드는 `totalPayAmount` (즉 `order.getTotalPrice()` 결과)를 사용
6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 조회 메서드(`findPaymentByMerchantPayKey`, `isCompensationRequired`)에 INFO 추가 금지. 이유: 조회는 도메인 상태 전환 아님 (§3).
- `log.error()`/`log.warn()` 추가 금지. 이유: 보상 catch는 이미 적용된 `PaymentApprovalCompensationService`와 `PaymentApprovalAttemptService`에서 처리. 본 step에서 신규 catch 추가하지 않음.
- 이미 `@Slf4j` 적용된 `PaymentApprovalAttemptService`, `PaymentApprovalCompensationService`, `NaverPayApprovalService` 수정 금지. 이유: 본 step 범위 밖.
- 비즈니스 로직 변경 금지.
- 기존 테스트를 깨뜨리지 마라.
