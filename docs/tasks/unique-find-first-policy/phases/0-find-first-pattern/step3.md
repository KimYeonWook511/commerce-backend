# Step 3: payment-attempt-find-first

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/src/main/java/com/commerce/payment/application/PaymentAttemptService.java`
- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `/src/main/java/com/commerce/payment/domain/repository/PaymentAttemptRepository.java`
- `/src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `/src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java`

step 1 이 끝나 있어야 한다.

## 작업

`PaymentAttemptService` 의 `getOrCreateApproveAttempt` 와 `getOrCreateCancelAttempt` 양쪽을 **try-save-catch-find** 패턴에서 **find-first** 패턴으로 리팩토링한다. amount mismatch 검증은 find 분기로 이동한다. race 시 unique 위반은 안전망 500 으로 도달한다.

### 1. `getOrCreateApproveAttempt` 리팩토링

기존 구조 (라인 31-57):

```java
try {
    return paymentAttemptRepository.save(
        PaymentAttempt.createApproveRequested(merchantPayKey, paymentId, amount, provider)
    );
} catch (DuplicateKeyException ex) {
    PaymentAttempt existing = paymentAttemptRepository
        .findApproveAttempt(merchantPayKey, provider, paymentId)
        .orElseThrow(...);
    if (existing.getAmount() != amount) {
        ... AMOUNT_MISMATCH throw ...
    }
    return existing;
}
```

새 구조:

```java
return paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, paymentId)
    .map(existing -> {
        if (existing.getAmount() != amount) {
            log.warn("PaymentAttempt amount mismatch - key={}, type=APPROVE, existingAmount={}, requested={}",
                merchantPayKey, existing.getAmount(), amount);
            throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
        }
        return existing;
    })
    .orElseGet(() -> paymentAttemptRepository.save(
        PaymentAttempt.createApproveRequested(merchantPayKey, paymentId, amount, provider)
    ));
```

- 기존 `PAYMENT_ATTEMPT_NOT_FOUND` throw 분기는 새 구조에서 사라진다 (find 가 정상 흐름이므로 not-found 는 곧 신규 저장 케이스).
- amount mismatch 로깅 메시지 형식은 기존과 동일하게 유지한다.

### 2. `getOrCreateCancelAttempt` 리팩토링

`getOrCreateApproveAttempt` 와 동일 패턴으로 적용한다 (라인 62-88):

```java
return paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, paymentId)
    .map(existing -> {
        if (existing.getAmount() != cancelAmount) {
            log.warn("PaymentAttempt amount mismatch - key={}, type=CANCEL, existingAmount={}, requested={}",
                merchantPayKey, existing.getAmount(), cancelAmount);
            throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
        }
        return existing;
    })
    .orElseGet(() -> paymentAttemptRepository.save(
        PaymentAttempt.createCancelRequested(merchantPayKey, paymentId, cancelAmount, provider)
    ));
```

### 3. 임포트와 트랜잭션

- `import org.springframework.dao.DuplicateKeyException;` 제거.
- `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 어노테이션은 기존대로 유지 (find / save 가 각자 짧은 트랜잭션).
- `@Slf4j` 와 기존 로깅은 유지.

### 4. 단위 테스트 갱신 (`PaymentAttemptServiceTest.java`)

- 라인 57-75, 119-135, 137-155 의 `DuplicateKeyException` mock 케이스를 제거한다.
- 다음 시나리오들로 케이스를 교체한다:
  - `findApproveAttempt` 빈 결과 → save 호출되어 신규 attempt 반환
  - `findApproveAttempt` 결과 존재 + 같은 amount → 기존 attempt 반환 (save 호출 안 됨)
  - `findApproveAttempt` 결과 존재 + 다른 amount → `PaymentException(PAYMENT_ATTEMPT_AMOUNT_MISMATCH)` throw
  - cancel 쪽도 동일한 3가지 케이스
- `PaymentAttemptRepository` mock 의 `save` 가 race 시 unique 위반을 던지는 케이스는 본 단위 테스트에서 다루지 않는다 (통합 테스트와 안전망 도달은 step 5 에서 다룸).

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `PaymentAttemptService` 에 `org.springframework.dao.DuplicateKeyException` 임포트가 없는가?
   - find-first 흐름이 정상 멱등/신규/amount mismatch 세 케이스를 모두 다루는가?
   - 단위 테스트가 새 시나리오로 갱신되어 모두 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- amount mismatch 정책을 변경하지 마라. 이유: PR #101 (`payment-attempt-idempotency`) 에서 정의된 정책이다. 위치만 catch 내부 → find 분기로 옮길 뿐, throw 하는 ErrorCode 와 메시지 형식은 그대로 유지한다.
- `PaymentAttemptRepository.findApproveAttempt` / `findCancelAttempt` 시그니처를 변경하지 마라. 이유: 다른 서비스에서도 사용한다.
- `Propagation.NOT_SUPPORTED` 어노테이션을 제거하지 마라. 이유: find / save 가 각자 짧은 트랜잭션에서 동작해야 race window 안전망 정책이 유지된다.
- 기존 테스트를 깨뜨리지 마라.
