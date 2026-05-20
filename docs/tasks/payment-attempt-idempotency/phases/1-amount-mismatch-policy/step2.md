# Step 3: concurrency-test

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/features/payment-attempt-idempotency/prd.md`
- `docs/features/payment-attempt-idempotency/architecture.md`

그 다음 실제 수정 대상 파일을 읽어라:

- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java` (step1에서 수정된 상태)
- `src/test/java/com/commerce/payment/application/concurrency/PaymentAttemptServiceConcurrencyTest.java`

## 작업

`PaymentAttemptServiceConcurrencyTest`에 동시 다른 amount 시나리오 2개를 추가한다.

### 패턴

기존 동시성 테스트는 모든 스레드가 같은 amount로 시도해 1건만 저장됨을 검증한다.
이번 테스트는 **미리 저장된 attempt(amount=X)에 대해 다른 amount(Y)로 동시 재요청**을 보내 모두 mismatch 예외를 받는지 검증한다.

### 추가 테스트 1: APPROVE amount mismatch

```java
@DisplayName("기존 승인 attempt와 다른 금액으로 동시 요청하면 모두 금액 불일치 예외가 발생한다")
@Test
void getOrCreateApproveAttempt_whenConcurrentRequestWithDifferentAmount_allThrowAmountMismatch() throws Exception {
    // given: amount=1000으로 approve attempt 선행 생성
    String merchantPayKey = "PAY-ATTEMPT-MISMATCH-1";
    String paymentId = "pg-attempt-mismatch-1";
    paymentAttemptService.getOrCreateApproveAttempt(
        merchantPayKey, PaymentProvider.NAVERPAY, paymentId, 1000);

    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    // when: 20개 스레드가 amount=2000으로 동시 재요청 (mismatch)
    runConcurrent(20, () -> paymentAttemptService.getOrCreateApproveAttempt(
        merchantPayKey, PaymentProvider.NAVERPAY, paymentId, 2000), errors);

    // then: attempt는 1건, 재요청 20개 모두 mismatch 예외
    assertThat(paymentPersistence.countAttempts(merchantPayKey, paymentId, PaymentAttemptType.APPROVE))
        .isEqualTo(1L);
    assertThat(errors).hasSize(20);
    errors.forEach(e -> {
        assertThat(e).isInstanceOf(PaymentException.class);
        assertThat(((PaymentException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
    });
}
```

### 추가 테스트 2: CANCEL amount mismatch

동일 패턴. merchantPayKey, paymentId, 스레드 수 등은 겹치지 않는 별도 키를 사용한다.

## Acceptance Criteria

```bash
./gradlew dockerTest --tests "com.commerce.payment.application.concurrency.PaymentAttemptServiceConcurrencyTest"
```

이 테스트는 Docker가 필요하다. `@Tag("concurrency")` `@Tag("docker")`가 붙어 있으므로 `dockerTest` 태스크로만 실행된다.

## 검증 절차

1. 위 명령을 실행한다.
2. 아래를 확인한다:
   - 기존 2개 동시성 케이스(같은 amount) 회귀 없음
   - 새로 추가한 2개 케이스(다른 amount) 모두 통과
   - errors queue 크기가 정확히 20인지 확인
3. 결과에 따라 step 상태를 갱신한다.

## 커밋

```
test: PaymentAttempt 멱등 재요청 금액 불일치 동시성 테스트를 추가한다
```

## 금지사항

- 기존 동시성 테스트 케이스를 수정하거나 삭제하지 마라. 이유: 같은 amount 재요청은 기존 동작(정상 반환)이 유지되어야 한다.
- `@Tag` 어노테이션을 임의로 제거하지 마라. 이유: 동시성 테스트는 Docker 환경에서만 실행하도록 분리되어 있다.
