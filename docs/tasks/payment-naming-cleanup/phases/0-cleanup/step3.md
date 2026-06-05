# Step 3: purge-payment-attempt-identifiers

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/payment-naming-cleanup/prd.md`
- `/docs/tasks/payment-naming-cleanup/architecture.md`
- `/docs/tasks/payment-naming-cleanup/adr.md` (특히 ADR-2, ADR-3)
- `src/main/java/com/commerce/payment/application/PaymentApprovalAttemptService.java`
- `src/main/java/com/commerce/payment/application/PaymentCancellationAttemptService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`

## 작업

옛 `PaymentAttempt` 엔티티를 가리키던 `attempt` 식별자를 전면 제거한다 (ADR-2).

### 적용 기준 (entity-reference rule)

- 식별자/표현이 **`Payment` 엔티티(=옛 PaymentAttempt)** 를 가리키면 정리한다.
- **진짜 시도(try)** 를 뜻하면 보존한다 (아래 KEEP LIST).
- 변수의 선언 타입이 `Payment` 이거나 그 식별자가 `Payment` 행을 가리키면 정리 대상이다.

### A. main 코드 식별자 (whole-identifier rename)

| Before | After |
|---|---|
| `attempt` (Payment 타입 지역변수/파라미터) | `payment` |
| `approveAttempt` | `approvePayment` |
| `cancelAttempt` | `cancelPayment` |
| `findApproveAttempt` | `findApprovePayment` |
| `findCancelAttempt` | `findCancelPayment` |
| `processApproveAttempt` | `processApprovePayment` |

- `attempt` → `payment` 는 단어 경계 단위로 바꾼다. 같은 스코프에 다른 `payment` 변수가 있어 충돌하면 보고한다(임의 변형 금지).
- `PaymentRepository`(인터페이스)와 `PaymentRepositoryAdapter`(구현) 양쪽의 메서드명을 함께 바꾼다. 호출처(`PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `PaymentApprovalService`, `NaverPayApprovalService`)도 모두 반영한다.

### B. 서비스 클래스 rename (파일+클래스+생성자+참조+주입 필드)

| Before | After |
|---|---|
| `PaymentApprovalAttemptService` | `PaymentApprovalRecordService` |
| `PaymentCancellationAttemptService` | `PaymentCancellationService` |
| 주입 필드 `paymentApprovalAttemptService` | `paymentApprovalRecordService` |
| 주입 필드 `paymentCancellationAttemptService` | `paymentCancellationService` |

- `.java` 파일명도 클래스명에 맞춰 rename한다.
- `@Service` 빈 이름은 클래스명에서 자동 파생되므로 별도 처리 불필요하나, 명시적 빈 이름 지정이 있으면 함께 바꾼다.
- 주입처: `NaverPayApprovalService`, `PaymentApprovalCompensationService`.

### C. 에러코드 enum 식별자 (`PaymentErrorCode`)

| Before | After |
|---|---|
| `PAYMENT_ATTEMPT_NOT_FOUND` | `PAYMENT_RECORD_NOT_FOUND` |
| `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` | `PAYMENT_RECORD_AMOUNT_MISMATCH` |
| `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED` | `PAYMENT_STATUS_TRANSITION_NOT_ALLOWED` |

- **`code` 문자열(`PAYMENT-404-2`, `PAYMENT-409-3`, `PAYMENT-500-1`)과 한국어 메시지("결제 시도 이력...")는 그대로 둔다.** enum 상수 이름만 바꾼다.
- 사용처(`Payment.java`, 서비스들, 테스트)의 `PaymentErrorCode.PAYMENT_ATTEMPT_*` 참조를 모두 반영한다.

### D. main 주석·로그 텍스트

- `Payment` 를 가리키는 영어 "attempt" 주석을 `payment`/결제 로 다듬는다 (예: `NaverPayApprovalService` 의 "기존 APPROVE attempt를 상태별로 재처리", `PaymentApprovalCompensationService` 의 "원 approve attempt 를 FAILED 마킹", `PaymentApprovalService` 의 "attempt.succeed() + order.completePayment()", `PaymentApprovalAttemptService` 의 "기존 attempt를 반환").
- 로그 텍스트 `"attemptAmount=%d"`(`PaymentApprovalCompensationService.compensateAmountMismatch`) → `"approveAmount=%d"`.
- 한국어 "시도" 는 그대로 둔다.

### E. test 코드 식별자 + 클래스 파일 rename

대상: `src/test/java/com/commerce/payment/**` (단 `postprocess` 패키지 제외).

테스트 클래스 rename (파일+클래스+내부 참조):

| Before | After |
|---|---|
| `PaymentApprovalAttemptServiceTest` | `PaymentApprovalRecordServiceTest` |
| `PaymentApprovalAttemptServiceConcurrencyTest` | `PaymentApprovalRecordServiceConcurrencyTest` |
| `PaymentCancellationAttemptServiceTest` | `PaymentCancellationServiceTest` |
| `PaymentCancellationAttemptServiceConcurrencyTest` | `PaymentCancellationServiceConcurrencyTest` |
| `PaymentRepositoryDuplicateAttemptTest` | `PaymentRepositoryDuplicatePaymentTest` |

헬퍼/변수/메서드명: `Payment` 를 가리키는 식별자의 `Attempt`/`attempt` 토큰을 `Payment`/`payment` 로 바꾼다. 예:
- `createAttempt`/`createApproveAttempt`/`createCancelAttempt` → `createPayment`/`createApprovePayment`/`createCancelPayment`
- `failedApproveAttempt`/`failedCancelAttempt`, `completedAttempt`, `existingAttempt`, `succeededAttempt`, `requestedAttempt`, `approveRequestedAttempt`, `cancelRequestedAttempt`, `amountMismatchAttempt`, `updateAttempt`, `returnAttempt` 등 → 대응 `...Payment`
- 테스트 메서드명 `whenApproveAttemptSucceeded`, `whenCancelAttemptRequested` 등 `Payment` 시나리오를 가리키는 것 → `whenApprovePaymentSucceeded`, `whenCancelPaymentRequested`

### F. test 주석·@DisplayName

- `Payment` 행을 가리키는 영어 "approve attempt"/"cancel attempt"/"attempt" 표현을 `approve payment`/`cancel payment`/`payment` 로 다듬는다.
- **한국어 "시도"("결제 시도 이력", "결제 시도를 실패로 기록한다" 등)는 그대로 둔다.**

### KEEP LIST — 절대 바꾸지 않는다

- `attackerAttempt` (보안 테스트의 공격 시도) 및 그 파생.
- concurrent/동시 시도, retry 시도처럼 `Payment` 가 아니라 행위의 "시도(try)" 를 뜻하는 식별자/표현 (예: `whenConcurrentAttemptSucceededAndPaymentMissing` 의 "concurrent attempt"). 의미가 모호하면 보고한다.
- 한국어 "시도" 표현 전부.
- test-only `postprocess` 패키지 전체 (`src/test/java/com/commerce/payment/postprocess/**`).
- `docs/adr.md`, migration 파일, `docs/logging-conventions.md`, `docs/db-schema.md` 의 outbox `attempt_count`. (이 step은 코드만 다룬다)

## Acceptance Criteria

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

`integrationTest` 를 포함하는 이유: repository 조회 메서드명 변경과 에러코드 식별자 변경이 통합 테스트 경로에 영향을 줄 수 있다.

## 검증 절차

1. `./gradlew test` 와 `./gradlew integrationTest` 가 통과하는지 확인한다.
2. 잔재가 사라졌는지 확인한다. 아래 결과에 KEEP LIST(`attackerAttempt`, concurrent attempt, postprocess) 항목만 남아야 한다:
   ```bash
   rg -nw "attempt|approveAttempt|cancelAttempt" src/main/java/com/commerce/payment
   rg -n "findApproveAttempt|findCancelAttempt|processApproveAttempt" src/main/java src/test/java
   rg -n "PaymentApprovalAttemptService|PaymentCancellationAttemptService|PAYMENT_ATTEMPT_" src/main/java src/test/java
   ```
3. 에러코드의 `code` 문자열과 한국어 메시지가 보존됐는지 확인한다 (`PaymentErrorCode` diff에서 `PAYMENT-404-2` 등과 메시지는 불변).
4. `postprocess` 패키지가 변경되지 않았는지 확인한다.
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 에러코드의 `code` 문자열·HTTP status·한국어 메시지를 바꾸지 마라. 이유: 외부 계약이다.
- KEEP LIST 항목을 바꾸지 마라. 이유: 진짜 "시도(try)" 또는 미래 정비 대상(postprocess)·역사 기록이다.
- 한국어 "시도" 를 바꾸지 마라. 이유: 자연스러운 도메인 표현이며 유지하기로 결정했다.
- `save()`/`saveAndFlush` 호출을 추가·제거하지 마라. 이유: flush 타이밍이 이중결제 보상 catch의 load-bearing 요소다.
- `PaymentApprovalService`(기존, 승인 성공 오케스트레이션)를 rename하지 마라. 이유: rename 대상은 `...AttemptService` 2개뿐이다.
- 의미가 모호한 `attempt` 는 임의 판단하지 말고 보고하라.
- 기존 테스트를 깨뜨리지 마라.
