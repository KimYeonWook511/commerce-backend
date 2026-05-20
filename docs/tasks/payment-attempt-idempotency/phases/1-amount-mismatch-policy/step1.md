# Step 1: amount-mismatch-policy

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/features/payment-attempt-idempotency/prd.md`
- `docs/features/payment-attempt-idempotency/architecture.md`
- `docs/features/payment-attempt-idempotency/adr.md`

그 다음 실제 수정 대상 파일을 읽어라:

- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java`
- `src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java`

## 작업

### 1. `PaymentErrorCode.java` — 신규 enum 값 추가

기존 409 코드 목록(`PAYMENT_STATUS_NOT_ALLOWED`, `PAYMENT_DUPLICATE`) 뒤에 아래를 추가한다:

```java
PAYMENT_ATTEMPT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "PAYMENT-409-3",
    "결제 시도 이력의 금액과 요청 금액이 일치하지 않습니다"),
```

### 2. `PaymentAttemptService.java` — catch 블록 보강

클래스에 `@Slf4j`를 추가한다.

`getOrCreateApproveAttempt` catch 블록을 아래와 같이 교체한다:

```java
} catch (DataIntegrityViolationException ex) {
    PaymentAttempt existing = paymentAttemptRepository
        .findApproveAttempt(merchantPayKey, provider, paymentId)
        .orElseThrow(() -> {
            log.error("unique 충돌 후 approve attempt 재조회 실패: merchantPayKey={}, paymentId={}",
                merchantPayKey, paymentId, ex);
            return new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
        });
    if (existing.getAmount() != amount) {
        log.warn("PaymentAttempt amount mismatch — key={}, type=APPROVE, existing={}, requested={}",
            merchantPayKey, existing.getAmount(), amount);
        throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
    }
    return existing;
}
```

`getOrCreateCancelAttempt` catch 블록도 동일 패턴으로 교체한다 (`findCancelAttempt` 사용, log 메시지에 `type=CANCEL`).

### 3. `PaymentAttemptServiceTest.java` — 테스트 추가/수정

**추가 (2건)**:

```java
@DisplayName("승인 시도 생성 중 유니크 충돌이 나고 기존 amount와 다르면 예외를 던진다")
@Test
void getOrCreateApproveAttempt_whenDuplicateOnSaveWithDifferentAmount_throwAmountMismatch() {
    // given
    PaymentAttempt existing = PaymentAttempt.createApproveRequested(
        "PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
    given(paymentAttemptRepository.findApproveAttempt(
        eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
        .willReturn(Optional.of(existing));
    given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
        .willThrow(new DataIntegrityViolationException("duplicate key"));

    // when & then
    assertThatThrownBy(() -> paymentAttemptService.getOrCreateApproveAttempt(
        "PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 2000))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
}
```

cancel 동일 패턴 (`getOrCreateCancelAttempt_whenDuplicateOnSaveWithDifferentAmount_throwAmountMismatch`).

**수정 (1건)**:

`getOrCreateCancelAttempt_whenDuplicateOnSaveAndRefetchMissing_throwException` (line 117–131):
- 기존: `.isInstanceOf(DataIntegrityViolationException.class)` 기대
- 변경: `.isInstanceOf(PaymentException.class)` + `.extracting(e -> ((PaymentException) e).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND)` 기대

## 커밋 분리

이 step에서 두 개의 커밋을 분리해 생성한다.

**커밋 1**: 정책 변경

```
fix: PaymentAttempt 멱등 재요청의 금액 불일치를 명시적 예외로 처리한다
```

포함 파일:
- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java` (catch 블록 + @Slf4j)
- `src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java`

**커밋 2**: 명명 통일 (별도 목적)

```
refactor: PaymentAttemptService 파라미터 명명을 엔티티 기준으로 통일한다
```

포함 파일:
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java` (파라미터 명명만)

변경 대상: `succeedApproveAttempt`, `failApproveAttempt`의 `pgPaymentId` 파라미터 → `paymentId`.
내부 변수명도 함께 변경.
`succeedCancelAttempt`, `failCancelAttempt`는 이미 `paymentId` 사용 중 — 변경 없음.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 명령을 실행한다.
2. 아래를 확인한다:
   - `PaymentAttemptServiceTest` 전체 통과
   - `NaverPay` 관련 기존 테스트 회귀 없음
   - 새로 추가된 2개 케이스와 수정된 1개 케이스가 모두 통과
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `Payment.pgPaymentId` 필드와 `NaverPayApproveResponse.pgPaymentId`를 변경하지 마라. 이유: 내부 도메인 명명(`pgPaymentId`)과 PG API 스펙 명명(`paymentId`)은 의도된 분리다.
- `PAYMENT_AMOUNT_MISMATCH`를 재사용하지 마라. 이유: PG 응답 mismatch(외부 원인)와 호출자 측 mismatch(내부 원인)는 의미와 모니터링 기준이 다르다.
- 기존 "같은 amount 재조회 반환" 테스트를 깨뜨리지 마라.
