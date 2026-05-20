# Step 2: split-payment-attempt-service

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/tasks/payment-attempt-service-split/prd.md`
- `docs/tasks/payment-attempt-service-split/architecture.md`
- `docs/tasks/payment-attempt-service-split/adr.md`
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java` ← step 1에서 갱신된 버전
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java`
- `src/test/java/com/commerce/payment/application/concurrency/PaymentAttemptServiceConcurrencyTest.java`

## 작업

### 1. `PaymentApprovalAttemptService` 신설 (`src/main/java/com/commerce/payment/application/PaymentApprovalAttemptService.java`)

클래스 레벨 `@Transactional`을 붙이지 않는다. 각 메서드에 트랜잭션을 명시해 경계가 한 눈에 보이도록 한다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentAttempt getOrCreate(String merchantPayKey, PaymentProvider provider,
                                      String paymentId, int amount) { ... }

    @Transactional
    public void succeed(String merchantPayKey, PaymentProvider provider,
                        String paymentId, LocalDateTime respondedAt) { ... }

    @Transactional
    public void fail(String merchantPayKey, PaymentProvider provider,
                     String paymentId, PaymentAttemptFailCode failCode,
                     String failDetail, LocalDateTime respondedAt) { ... }

    /**
     * 보상 흐름 전용: REQUESTED 상태일 때만 실패 처리하고, 그 외 상태이거나 이력이 없으면 조용히 skip한다.
     */
    @Transactional
    public void failIfRequested(String merchantPayKey, PaymentProvider provider,
                                String paymentId, PaymentAttemptFailCode failCode,
                                String failDetail, LocalDateTime respondedAt) { ... }
}
```

- `getOrCreate` 내부: `paymentAttemptRepository.findApproveAttempt(...)` → amount 검증 → 없으면 `PaymentAttempt.createApproveRequested(...)` 로 save
- `succeed` 내부: `findApproveAttempt` orElseThrow → `attempt.succeed(respondedAt)`
- `fail` 내부: `findApproveAttempt` orElseThrow → `attempt.fail(failCode, failDetail, respondedAt)`
- `failIfRequested` 내부: `findApproveAttempt` orElse(null) → null이면 log.warn + return → `status != REQUESTED`이면 log.warn + return → `attempt.fail(...)`
- log 메시지 패턴은 기존 `PaymentAttemptService`와 동일하게 유지

### 2. `PaymentCancellationAttemptService` 신설 (`src/main/java/com/commerce/payment/application/PaymentCancellationAttemptService.java`)

동일하게 클래스 레벨 `@Transactional` 없이 메서드별 명시.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancellationAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentAttempt getOrCreate(String merchantPayKey, PaymentProvider provider,
                                      String paymentId, int cancelAmount) { ... }

    @Transactional
    public void succeed(String merchantPayKey, PaymentProvider provider,
                        String paymentId, LocalDateTime respondedAt) { ... }

    @Transactional
    public void fail(String merchantPayKey, PaymentProvider provider,
                     String paymentId, PaymentAttemptFailCode failCode,
                     String failDetail, LocalDateTime respondedAt) { ... }
}
```

- `getOrCreate` 내부: `paymentAttemptRepository.findCancelAttempt(...)` → amount 검증 → 없으면 `PaymentAttempt.createCancelRequested(...)` 로 save
- `succeed` 내부: `findCancelAttempt` orElseThrow → `attempt.succeed(respondedAt)`
- `fail` 내부: `findCancelAttempt` orElseThrow → `attempt.fail(failCode, failDetail, respondedAt)`

### 3. `PaymentAttemptService.java` 삭제

step 1에서 갱신된 `PaymentAttemptService.java`를 삭제한다.

### 4. 호출처 갱신

**(a) `PaymentApprovalService` (`src/main/java/com/commerce/payment/application/PaymentApprovalService.java`)**

- `PaymentAttemptService` 의존성 → `PaymentApprovalAttemptService` 의존성으로 교체
- `completeApprovedPayment` 내부: `paymentAttemptService.succeedApproveAttempt(...)` → `paymentApprovalAttemptService.succeed(...)`
- **클래스 레벨 `@Transactional(readOnly = true)` 제거** + 각 메서드에 명시:
  - `findPaymentByMerchantPayKey` → `@Transactional(readOnly = true)`
  - `isCompensationRequired` → `@Transactional(readOnly = true, propagation = REQUIRES_NEW)` (기존 유지)
  - `completeApprovedPayment` → `@Transactional` (기존 유지)

**(b) `NaverPayApprovalService` (`src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`)**

- `PaymentAttemptService` 의존성 → `PaymentApprovalAttemptService` + `PaymentCancellationAttemptService` 의존성으로 교체
- 모든 호출 치환:
  - `paymentAttemptService.getOrCreateApproveAttempt(...)` → `paymentApprovalAttemptService.getOrCreate(...)`
  - `paymentAttemptService.failApproveAttemptIfRequested(...)` → `paymentApprovalAttemptService.failIfRequested(...)`
  - `paymentAttemptService.getOrCreateCancelAttempt(...)` → `paymentCancellationAttemptService.getOrCreate(...)`
  - `paymentAttemptService.succeedCancelAttempt(...)` → `paymentCancellationAttemptService.succeed(...)`
  - `paymentAttemptService.failCancelAttempt(...)` → `paymentCancellationAttemptService.fail(...)`

### 5. 테스트 분할

**(a) `PaymentAttemptServiceTest` 삭제 및 분할**

- `PaymentApprovalAttemptServiceTest` 신설 (`src/test/java/com/commerce/payment/application/PaymentApprovalAttemptServiceTest.java`): approve 관련 케이스 (getOrCreate, succeed, fail, failIfRequested 시나리오)
- `PaymentCancellationAttemptServiceTest` 신설 (`src/test/java/com/commerce/payment/application/PaymentCancellationAttemptServiceTest.java`): cancel 관련 케이스 (getOrCreate, succeed, fail 시나리오)
- `PaymentAttemptServiceTest.java` 삭제
- BDDMockito 스타일 유지 (`given()`, `then().should()`)

**(b) `PaymentAttemptServiceConcurrencyTest` 분할**

- `PaymentApprovalAttemptServiceConcurrencyTest` 신설 (`src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalAttemptServiceConcurrencyTest.java`): approve 관련 동시성 테스트 2개 (`getOrCreate_whenConcurrentIdempotentRequest_returnSameApproveAttempt`, `getOrCreate_whenConcurrentRequestWithDifferentAmount_allThrowAmountMismatch`)
- `PaymentCancellationAttemptServiceConcurrencyTest` 신설 (`src/test/java/com/commerce/payment/application/concurrency/PaymentCancellationAttemptServiceConcurrencyTest.java`): cancel 관련 동시성 테스트 2개
- `PaymentAttemptServiceConcurrencyTest.java` 삭제
- `@Tag("concurrency")`, `@Tag("docker")`, `@SpringBootTest`, `@ActiveProfiles("test")` 그대로 유지

**(c) `PaymentApprovalServiceTest` 갱신 (`src/test/java/com/commerce/payment/application/PaymentApprovalServiceTest.java`)**

- `paymentAttemptService.succeedApproveAttempt(...)` 호출 검증 → `paymentApprovalAttemptService.succeed(...)` 호출 검증으로 갱신

**(d) `NaverPayApprovalServiceTest` 호출명 갱신 (`src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`)**

- `paymentAttemptService.*` 호출 검증을 새 Service 호출 검증으로 갱신

**(e) `NaverPayServiceConcurrencyTest` 갱신 (`src/test/java/com/commerce/payment/naverpay/application/concurrency/NaverPayServiceConcurrencyTest.java`)**

- 의존 주입명이 바뀐 경우 수정. 동시성 시나리오 의미는 그대로 유지

**(f) `NaverPayServiceIntegrationTest` 갱신 (`src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java`)**

- Service 분리에 따른 의존 주입명 갱신

## Acceptance Criteria

```bash
./gradlew test
```

동시성 테스트 별도 실행 (docker 필요):

```bash
./gradlew dockerTest
```

## 검증 절차

1. 위 Acceptance Criteria를 실행한다.
2. 아래를 확인한다:
   - `PaymentAttemptService.java`가 삭제됐는가?
   - `PaymentApprovalAttemptService` / `PaymentCancellationAttemptService`가 각자 3-4개 메서드의 단순 구조인가?
   - 두 Service 모두 클래스 레벨 `@Transactional`이 없고, `getOrCreate`에 `NOT_SUPPORTED`, `succeed`/`fail`/`failIfRequested`에 `REQUIRED`가 정확히 붙어 있는가?
   - 공유 도메인 계약 변경: `rg "PaymentAttemptService" src/main/java src/test/java` 결과가 0이어야 한다
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `@Transactional` 어노테이션을 임의로 변경하지 마라. 이유: `NOT_SUPPORTED`가 사라지면 race-safe한 find-first 정책이 깨진다
- `NaverPayApprovalService`의 보상 메서드(`compensate*`, `failApproveAndCancelApprovedPayment`)를 건드리지 마라. 이유: task B 범위
- 기존 테스트를 깨뜨리지 마라
