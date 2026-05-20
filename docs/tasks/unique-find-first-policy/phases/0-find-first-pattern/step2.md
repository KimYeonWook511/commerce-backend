# Step 3: remove-direct-catch-blocks

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/src/main/java/com/commerce/member/application/MemberRegistrationService.java`
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `/src/main/java/com/commerce/member/exception/MemberErrorCode.java`
- `/src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `/src/test/java/com/commerce/member/application/MemberRegistrationServiceTest.java`
- `/src/test/java/com/commerce/payment/application/PaymentApprovalServiceTest.java`
- `/src/test/java/com/commerce/payment/application/PaymentApprovalServiceIntegrationTest.java`

step 1 에서 안전망 보강이 끝나 있어야 한다.

## 작업

`MemberRegistrationService` 와 `PaymentApprovalService` 는 변경 형태가 동일하다: `DuplicateKeyException` catch 한 블록을 제거하고, 그 직전의 사전 체크(`existsByEmail` / `findByMerchantPayKey`) 분기는 유지한다. race window 시 unique 위반은 안전망 500 으로 도달하게 된다.

### 1. `MemberRegistrationService.java` 수정

- `try/catch (DuplicateKeyException ex) { throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL); }` 블록 제거.
- `memberRepository.save(member)` 호출은 try 없이 직접 반환한다.
- `import org.springframework.dao.DuplicateKeyException;` 임포트 제거.
- 라인 24 의 `existsByEmail` 사전 체크는 그대로 유지.

### 2. `PaymentApprovalService.java` 수정

- `try/catch (DuplicateKeyException ex) { throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE); }` 블록 제거.
- `paymentRepository.save(...)` 호출을 try 없이 직접 반환한다.
- `import org.springframework.dao.DuplicateKeyException;` 임포트 제거.
- 라인 44-50 의 사전 조회 + `validateCompletedPaymentOrThrow` 분기는 그대로 유지 (정상 멱등 흡수 경로).

### 3. 단위 테스트 갱신

#### `MemberRegistrationServiceTest.java`

- "save 시 `DuplicateKeyException` → `DUPLICATE_EMAIL`" 케이스(라인 79-99) 를 제거한다.
- `existsByEmail` 사전 체크 분기 검증은 유지한다.

#### `PaymentApprovalServiceTest.java`

- `completeApprovedPayment_whenDuplicateOnSave_throwDuplicateException` 같은 PAYMENT_DUPLICATE race 케이스(라인 134-155) 를 제거한다.
- 사전 조회 + `validateCompletedPaymentOrThrow` 분기 검증은 유지한다.

#### `PaymentApprovalServiceIntegrationTest.java`

- 라인 73-101 의 race 케이스를 동일하게 정리한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `MemberRegistrationService` 와 `PaymentApprovalService` 에 `org.springframework.dao.DuplicateKeyException` 임포트가 없는가?
   - 사전 체크 분기는 정상 동작하는가?
   - 변경한 단위 테스트가 모두 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 사전 체크(`existsByEmail`, `findByMerchantPayKey`) 분기를 함께 제거하지 마라. 이유: 정상 흐름의 중복 처리(4xx 응답) 가 이 분기에서 일어난다. catch 제거는 race window 한정 행위 변경만 수반해야 한다.
- `PaymentApprovalService.validateCompletedPaymentOrThrow` 의 검증 로직을 건드리지 마라. 이유: 정상 멱등 흡수 경로의 검증이며 본 step 범위 밖.
- 기존 도메인 ErrorCode(`DUPLICATE_EMAIL`, `PAYMENT_DUPLICATE`) 자체를 제거하지 마라. 이유: 사전 체크 분기에서 여전히 사용한다.
- 기존 테스트를 깨뜨리지 마라.
