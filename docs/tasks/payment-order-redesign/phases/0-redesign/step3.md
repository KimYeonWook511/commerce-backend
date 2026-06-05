# Step 3: approval-safety-net-and-idempotent-redirect

## 읽어야 할 파일

먼저 아래 파일들을 읽고 step 1 / step 2 결과를 파악하라:

- `docs/tasks/payment-order-redesign/prd.md`
- `docs/tasks/payment-order-redesign/architecture.md`
- `docs/tasks/payment-order-redesign/adr.md` (ADR-3 NULL 트릭, ADR-5 USED 멱등, ADR-6 UNKNOWN, ADR-8 트랜잭션 경계)
- `docs/testing-conventions.md` (동시성 테스트 작성 규칙)
- `docs/logging-conventions.md` (특히 보상 catch 1차/2차 예외 처리)
- `docs/exception-strategy.md` (보상 catch 규약)
- `src/main/java/com/commerce/payment/domain/Payment.java`
- `src/main/java/com/commerce/payment/domain/PaymentReservation.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/ReservePaymentService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java`
- `docs/adr.md` (ADR-013 보상 catch 2차 예외, ADR-014 보상 진행 판단, ADR-015 보상 정책 책임)

## 작업

이 step 은 세 가지를 처리한다:
- (1) `uk_payment_approved_order_key` 위반 보상 path 추가
- (2) UNKNOWN 마킹 흐름 + UNKNOWN 행 있는 주문 차단
- (3) USED Reservation 에 redirect 중복 도착 시 *멱등 응답 흡수*

### 1. `uk_payment_approved_order_key` 위반 보상 — `compensateDuplicateApproval`

경로: `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`

새 메서드:

```java
public void compensateDuplicateApproval(Payment attempt, PgCanceller pgCanceller) {
    log.error("이중 결제 감지 — 이미 결제된 주문 orderId={} merchantPayKey={}",
        attempt.getOrderId(), attempt.getMerchantPayKey());
    // ADR-014 보상 진행 판단: 이 attempt 는 PG 에서 돈이 빠졌으므로 cancel 필요
    // attempt 행은 아직 SUCCEEDED 가 못 됐을 가능성 (uk_payment_approved_order_key 가 막은 직후) — REQUESTED 상태
    // pgCanceller 호출해서 PG cancel + cancel attempt 행 기록
    Payment cancelAttempt = paymentCancellationAttemptService.getOrCreate(
        attempt.getOrderId(), attempt.getMerchantPayKey(), attempt.getProvider(),
        attempt.getPgPaymentId(), attempt.getAmount()
    );
    CancelOutcome outcome = pgCanceller.cancel(cancelAttempt, "Duplicate approval — already paid");
    handleCancelOutcome(cancelAttempt, outcome);
    // 원 attempt 는 FAILED 마킹 (DUPLICATE_PAYMENT)
    paymentApprovalAttemptService.failIfRequested(
        attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
        PaymentFailCode.DUPLICATE_PAYMENT, "Already paid for this order", LocalDateTime.now()
    );
}
```

- 트랜잭션 경계: 클래스 레벨 `@Transactional` 없음 유지 (ADR-015). 단계별 독립 commit
- `PgCanceller` / `CancelOutcome` 기존 그대로 사용
- `handleCancelOutcome` 은 기존 보상 path 의 공통 골격 — outcome.status 가 SUCCESS/PROCESSING/FAILED 에 따라 cancel attempt succeed/markUnknown/fail
- `log.error` 메시지는 한국어 + 영어 식별자 + `{}` placeholder (logging-conventions §메시지 작성 규칙)

### 2. `NaverPayApprovalService` 의 `uk_payment_approved_order_key` 위반 catch

경로: `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`

`completeVerifiedApproval` 의 catch 블록 확장. step 1 baseline 흐름:

```java
try {
    attempt.verifyApprovedResponse(responseMerchantPayKey, responseTotalAmount);
    attempt.succeed(LocalDateTime.now());   // approvedOrderKey=orderId 같은 UPDATE
    order.completePayment();
    return toResponse(...);
} catch (DataIntegrityViolationException ex) {
    // uk_payment_approved_order_key 위반 (이미 결제된 주문에 새 APPROVE 성공)
    log.error("uk_payment_approved_order_key 위반 — 이중 결제: orderId={} merchantPayKey={}",
        attempt.getOrderId(), attempt.getMerchantPayKey(), ex);
    paymentApprovalCompensationService.compensateDuplicateApproval(attempt, this::pgCancel);
    throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
} catch (PaymentException ex) {
    // 기존 case 들 (MERCHANT_KEY_MISMATCH, AMOUNT_MISMATCH, DUPLICATE)
    ...
}
```

- `DataIntegrityViolationException` import 는 *적어도 PG approve complete 트랜잭션 catch 안에서만* 허용
- Application 계층이 인프라 예외에 직접 의존하는 비용은 있지만 ADR-11 의 "안전망 500 위임" 과 달리 *명시적 보상* 이 필요한 시나리오라 catch 허용 (exception-strategy 의 예외적 허용 케이스)
- 로그: `log.error("...", ex)` — Throwable 마지막 인자, stack trace 보존 (logging-conventions §예외 인자 처리)

### 3. USED Reservation 멱등 응답 흡수

경로: `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`

step 1 baseline 에는 `findApproveSucceeded` 의 멱등 응답이 *어디서나 한 번* 체크되는 흐름이었음. B안에서는 *Reservation.status* 가 *멱등 판단의 1차 기준* 이 됨:

```java
public NaverPayApproveResponse approve(Long memberId, String merchantPayKey, String pgPaymentId) {
    PaymentReservation reservation = paymentReservationRepository.findByMerchantPayKey(merchantPayKey)
        .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

    if (!reservation.getMemberId().equals(memberId)) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_MEMBER_MISMATCH);
    }

    Order order = orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)
        .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

    // UNKNOWN 차단 (4번 참조)
    if (paymentRepository.existsUnknownByOrderId(reservation.getOrderId())) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
    }

    // USED Reservation 멱등 응답 흡수 — 같은 키로 redirect 가 두 번째로 도착한 경우
    if (reservation.getStatus() == PaymentReservationStatus.USED) {
        Payment existing = paymentRepository.findApproveSucceeded(merchantPayKey)
            .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return toResponse(existing);
    }

    // 여기부터 RESERVED → USED 전이 + APPROVE 행 INSERT (트랜잭션 안)
    return processApproveRequest(reservation, order, pgPaymentId);
}
```

- `USED` Reservation 발견 시 *차단이 아닌* 200 응답 (ADR-5 의 멱등 흡수 정책)
- `findApproveSucceeded(merchantPayKey)` 가 빈 결과를 주는 경우는 *USED 인데 SUCCEEDED Payment 가 없는* 불일치 — 이건 정합성 깨진 상황이라 `PAYMENT_NOT_FOUND` 로 처리 (안전망 500 으로 위임할 수도 있지만 일단 명시적 에러)

### 4. UNKNOWN 마킹 — PG approve timeout / 네트워크 단절

경로: `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`

`processApproveRequest` 의 NaverPayGateway.approve 호출 결과 분기에 UNKNOWN case 추가:

- 현재 `NaverPayApproveResult.Status` enum: `SUCCESS`, `FAILED`, `PROCESSING`, `ALREADY_COMPLETE`
- **신규 추가**: `UNKNOWN` — gateway 에서 timeout / 네트워크 단절 시 반환
- 처리:
  ```java
  case UNKNOWN -> {
      attempt.markUnknown(result.getFailDetail(), LocalDateTime.now());
      // 트랜잭션 안에서 markUnknown UPDATE 만 적용
      throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
  }
  ```

`NaverPayGateway` 구현에서 `RestClient` timeout / `IOException` catch → `NaverPayApproveResult.unknown(failDetail)` 반환하도록 구현 보강.

`NaverPayApproveResult` 에 `unknown(String failDetail)` 정적 팩토리 추가.

### 5. UNKNOWN 차단 — ReservePaymentService 진입

경로: `src/main/java/com/commerce/payment/application/ReservePaymentService.java`

step 1/2 의 `reserve` 흐름 진입부에 UNKNOWN 차단 추가:

```java
order.checkPayable();

if (paymentRepository.existsUnknownByOrderId(order.getId())) {
    throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
}

// 이후 findReusable / save 흐름
```

NaverPayApprovalService 도 이미 (3) 에서 같은 검사 추가.

### 6. `PaymentErrorCode` 추가

경로: `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`

- 신규: `PAYMENT_RESULT_PENDING` — HTTP 409, 메시지 "결제 결과 확인 중입니다. 잠시 후 주문 내역에서 확인해 주세요."
- 신규: `PAYMENT_MEMBER_MISMATCH` — HTTP 403, 메시지 "결제 정보와 회원이 일치하지 않습니다." (Reservation.memberId vs SecurityContext memberId 불일치)
- `GlobalExceptionHandler` 의 `CustomException` 핸들러로 자연스럽게 처리되는지 확인 (별도 핸들러 추가 불필요)

### 7. 테스트

> **테스트 작성 체크리스트**: step 1 의 §15 참조. 핵심: Testcontainers MySQL / 동시성 두 태그 / unique 자손 예외 / 멱등 응답 *부작용 0회* + *응답 본문 동등성* 함께 단언 / "에러 아닌 멱등" 케이스는 *예외가 안 난다* 도 단언.

#### `NaverPayApprovalServiceTest` (단위)

- `@ExtendWith(MockitoExtension.class)` + `@Mock` 으로 Repository / Gateway / CompensationService
- `@DisplayName` + `행위_조건_결과` 메서드명:
  - `approve_whenReservationUsedAndApproveSucceededExists_returnsIdempotentResponse` — USED Reservation + Payment SUCCEEDED 존재 → 멱등 200 응답. 단언 강화:
    - `then(naverPayGateway).should(never()).approve(any())` (PG 호출 0회)
    - `then(paymentRepository).should(never()).save(any())` + `then(paymentReservationRepository).should(never()).save(any())` (새 INSERT / markUsed UPDATE 0회)
    - 응답 본문이 *기존 SUCCEEDED Payment 와 동등* — `assertThat(response).usingRecursiveComparison().isEqualTo(toResponse(existing))`
    - `assertThatNoException().isThrownBy(...)` 로 *예외 안 던짐* 단언
  - `approve_whenReservationMemberMismatch_throwsPaymentMemberMismatch`
  - `approve_whenUnknownByOrderId_throwsPaymentResultPending` — `existsUnknownByOrderId=true` → 차단, gateway 호출 0회
  - `approve_whenUkPaymentApprovedOrderKeyViolation_callsCompensateDuplicateApproval` — `DataIntegrityViolationException` mock → compensation 호출 검증 + `PAYMENT_DUPLICATE` throw
  - `approve_whenGatewayReturnsUnknown_callsMarkUnknownAndThrowsResultPending`

#### `ReservePaymentServiceTest` (단위, step 2 본문 보강)

- `reserve_whenUnknownByOrderId_throwsPaymentResultPending` — `existsUnknownByOrderId=true` → 차단, save 호출 0회

#### `PaymentApprovalCompensationServiceTest` (단위)

- `compensateDuplicateApproval_whenPgCancelSucceeds_*` / `_whenPgCancelProcessing_*` / `_whenPgCancelFails_*` 분기 모두
- pgCanceller `@Mock` + BDDMockito stub

#### 통합 테스트 — `uk_payment_approved_order_key` 위반

경로: `src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryApprovedConcurrencyTest.java`

- `@Tag("docker")` + `@Tag("concurrency")` (환경 + 격리)
- Testcontainers MySQL
- `tearDown` 에서 `PersistenceCleanupTestSupport.deleteAllInBatch(...)` 호출 (`@Transactional` 금지)
- 동시성 컨벤션 (testing-conventions §동시성) *불변식 단언 패턴*:
  - N thread 동시에 같은 orderId 로 `type=APPROVE + status=SUCCEEDED + approved_order_key=orderId` save
  - `CountDownLatch` 로 동시 시작 / 종료 대기
  - invariant 단언: *DB 에 SUCCEEDED APPROVE 행이 정확히 1 개*, 나머지는 `DataIntegrityViolationException`
- 메서드명: `succeedApprovePayment_whenConcurrent_onlyOneSucceedsAndOthersFailUniqueViolation`
- `@DisplayName`: "같은 주문에 APPROVE 가 동시에 SUCCEEDED 로 전이되어도 정확히 1개만 살아남는다"

#### 통합 테스트 — 복합 unique 위반 (`uk_payment_merchant_pay_key_provider_pg_payment_id_type`)

경로: `src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryDuplicateAttemptTest.java`

- `@Tag("docker")` (격리 태그 없음 — *순차* 시나리오)
- Testcontainers MySQL
- `tearDown` 에서 `PersistenceCleanupTestSupport.deleteAllInBatch(...)` 호출 (`@Transactional` 금지)
- 시나리오: 같은 `(merchantPayKey, provider, pgPaymentId, type=APPROVE)` 행을 *순차* 로 두 번 save → 두 번째 `DataIntegrityViolationException` 또는 자손
- 메서드명: `savePayment_whenSameAttemptKeySetExists_throwsUniqueViolation`
- `@DisplayName`: "같은 (merchantPayKey, provider, pgPaymentId, type) 조합으로 두 번째 INSERT 는 unique 위반으로 거부된다"

### 8. commit 분리

1. `feat: uk_payment_approved_order_key 위반 보상 path 를 추가한다` — compensateDuplicateApproval + NaverPayApprovalService catch
2. `feat: 결제 결과 UNKNOWN 마킹과 차단 흐름을 추가한다` — markUnknown + existsUnknownByOrderId + reserve/approve 차단 + 새 ErrorCode
3. `feat: USED Reservation 에 대한 redirect 멱등 응답을 추가한다` — NaverPayApprovalService 의 USED 분기
4. `test: uk_payment_approved_order_key 동시성 + UNKNOWN + 멱등 redirect 테스트를 추가한다`

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

## 검증 절차

1. 위 커맨드 모두 통과
2. `compensateDuplicateApproval` 의 *원 attempt FAILED 마킹 + cancel attempt 기록 + PG cancel 호출* 세 단계가 모두 들어있는지 확인
3. UNKNOWN 차단이 `ReservePaymentService.reserve` / `NaverPayApprovalService.approve` 진입 두 곳 모두에 적용됐는지 확인
4. `NaverPayGateway.approve` 의 timeout / IOException 경로가 `UNKNOWN` 으로 변환되는지 확인
5. USED Reservation 멱등 응답이 *차단이 아닌 200 OK + 기존 결제 결과* 로 동작하는지 확인
6. `PaymentMemberMismatch` 검증이 Reservation.memberId vs SecurityContext memberId 로 들어가는지 확인
7. 결과에 따라 step 상태 갱신

## 금지사항

- `compensateDuplicateApproval` 안에서 *원 attempt 의 succeed 를 다시 시도* 하지 마라. 이유: `uk_payment_approved_order_key` 가 막은 그 순간 이미 *다른* 행이 그 orderId 의 성공 자리를 차지했다. 이 attempt 의 의미는 *PG 에서 돈을 뺐지만 우리는 못 받는다* 이므로 FAILED + PG cancel 이 정답.
- UNKNOWN 차단을 *모든 read 경로* 에 박지 마라. 이유: 사용자가 *주문 내역 조회* 같은 read 경로에서 UNKNOWN 행을 봐도 차단이 아니라 *"확인 중"* 안내 흐름이 필요하다. 차단은 reserve / approve *추가 결제 시도* 진입점에만 둔다.
- UNKNOWN 자동 해소 로직 (대사 호출) 을 이 step 에 추가하지 마라. 이유: ADR-6 에서 후속 task 로 분리. 이번 PR 은 *흔적 보존 + 차단* 까지.
- `DataIntegrityViolationException` catch 를 다른 service / 다른 메서드로 퍼뜨리지 마라. 이유: ADR-11 의 *Application 이 인프라 예외에 의존하지 않음* 원칙. 이 step 의 catch 는 *uk_payment_approved_order_key 보상이 명시적으로 필요한* 한 지점만 예외적으로 허용.
- USED Reservation 에 *덮어쓰기 markUsed* 를 호출하지 마라. 이유: `markUsed` 의 선조건이 `status == RESERVED`. USED 인 행에 다시 호출하면 예외. 멱등 흐름은 *USED 면 즉시 기존 결과 반환* 이지 *다시 markUsed* 가 아님.
- USED Reservation 의 멱등 응답 분기를 *RESERVED → USED 트랜잭션 안에서* 처리하지 마라. 이유: 멱등 흡수는 *읽기만* 하면 됨. 트랜잭션 진입 비용을 아낀다.
- `failIfRequested` 가 던지는 예외를 catch 안에서 또 catch 하지 마라. 이유: 메서드 자체가 *예외 안 던지는 의도 캡슐화 (skip-if-not-requested)* 로 설계됨 (ADR-013).
- 보상 catch 안의 1차 예외 로그 (`log.error("...", ex)`) 의 throwable 마지막 인자 자리를 비우지 마라. 이유: SLF4J 관례 — 마지막 인자 Throwable 이 자동 stack trace 출력. 빠뜨리면 stack 손실 (logging-conventions §예외 인자 처리).
