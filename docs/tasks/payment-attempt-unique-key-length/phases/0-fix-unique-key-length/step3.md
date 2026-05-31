# Step 3: strengthen-concurrency-test-invariants

## 읽어야 할 파일

- `/docs/tasks/payment-attempt-unique-key-length/prd.md`
- `/docs/tasks/payment-attempt-unique-key-length/architecture.md`
- `/docs/tasks/payment-attempt-unique-key-length/adr.md`
- `/src/test/java/com/commerce/payment/naverpay/application/concurrency/NaverPayServiceConcurrencyTest.java`
- `/src/test/java/com/commerce/payment/infrastructure/persistence/support/PaymentPersistenceTestSupport.java`
- 이전 step 산출물.

태스크 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `/docs/testing-conventions.md`

## 작업

`NaverPayServiceConcurrencyTest`의 단언을 이중화한다. **production 코드는 변경하지 않는다.**

### (a) 데이터 invariant 추가

각 케이스의 `then` 섹션에 attempt 행 수가 정확히 1이 되어야 한다는 단언을 추가한다. 기존 `getAttempt`/`findAttempt` 호출보다 *앞에* 위치시켜, count가 깨지면 곧장 unique 누락이 노출되도록 한다.

- APPROVE attempt가 생성되는 케이스:
  ```java
  assertThat(paymentPersistence.countAttempts(merchantPayKey, paymentId, PaymentAttemptType.APPROVE))
      .isEqualTo(1L);
  ```
- CANCEL attempt가 생성되는 케이스: 기존에 이미 있는 `countCancelAttempts(merchantPayKey)` 검증을 그대로 두되, 케이스 의도(`REQUESTED`로 유지 / cancel 안 일어남 등)에 맞춰 0 또는 1이 되도록 단언한다.
- CANCEL attempt가 생성되지 않아야 하는 케이스: 기존 `findAttempt(...).isEmpty()` 검증을 유지하고, 그것이 곧 `count == 0` 의미와 동치임을 확인한다.

### (b) 행동 invariant 강화

기존 `assertRaceOrPaymentError` 헬퍼는 `DataIntegrityViolationException`을 무조건 통과시킨다.

```java
private static void assertRaceOrPaymentError(Throwable error, PaymentErrorCode... allowedDomainCodes) {
    if (error instanceof DataIntegrityViolationException) {
        return;
    }
    assertThat(error).isInstanceOf(PaymentException.class);
    assertThat(((PaymentException) error).getErrorCode()).isIn((Object[]) allowedDomainCodes);
}
```

이를 그대로 두되, **race가 실제로 발생한 것이 흐름상 보장되는 케이스**의 `then` 섹션 마지막에 다음 단언을 추가한다:

```java
assertThat(errors).anyMatch(e -> e instanceof DataIntegrityViolationException);
```

이로써 안전망(DB unique 위반 → 안전망 500)이 흐름상 실제로 한 번 이상 발동했음을 가시화한다.

### (c) 케이스별 분류

8개 케이스를 다음 세 분류로 식별하고 적용한다.

1. **race가 거의 항상 발생하는 케이스** (20 스레드가 같은 키로 동시 attempt 생성을 시도) → 데이터 invariant + 행동 anyMatch invariant 둘 다 추가.
2. **race가 발생할 수도 안 할 수도 있는 케이스** (예: 초기 attempt를 미리 만들어두고 시작하는 케이스) → 데이터 invariant만 추가. anyMatch는 추가하지 말고, 발생 시에는 helper로 흡수.
3. **race와 무관한 정합성 검증 케이스** (예: SUCCEEDED attempt + payment 없음 시 일관 예외 검증) → 기존 단언 + 데이터 invariant만 추가.

분류 판단 기준은 케이스 setup에서 attempt를 미리 저장하는지 (`paymentPersistence.save(PaymentAttempt.createApproveRequested(...))`) 여부와, `Mockito.doAnswer`/`doReturn` stubbing의 흐름이다.

## Acceptance Criteria

```bash
./gradlew concurrencyTest --tests "*NaverPayServiceConcurrencyTest*"
```

모든 8개 케이스가 통과해야 한다.

## 검증 절차

1. AC 명령을 실행한다.
2. 단언 추가 후에도 모든 케이스가 안정적으로 통과하는지 확인한다.
3. 회귀 검증: 의도적으로 `PaymentAttempt`의 `merchantPayKey` length를 255로 잠시 되돌려보고 AC가 실패하는지 한 번 확인. 확인 후 즉시 원상 복구.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 다른 concurrency 테스트(있다면)의 단언을 변경하지 마라. 이유: 본 task는 `NaverPayServiceConcurrencyTest`에 한정된다.
- 새로운 fixture 또는 helper를 추가하지 마라. 기존 `PaymentPersistenceTestSupport.countAttempts`, `countCancelAttempts`를 활용한다. 이유: scope 확장 방지.
- production 코드를 변경하지 마라. 이유: 본 step은 테스트 단언 강화만 담당한다.
- `assertRaceOrPaymentError` 헬퍼의 시그니처를 바꾸지 마라. 이유: 호출 위치가 다수이고, 현재 단언 정책은 race 흡수용으로 그대로 유지한다. 보강은 케이스별 anyMatch 추가로 한다.
