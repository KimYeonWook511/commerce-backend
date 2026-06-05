# Step 1: payment-and-reservation-domain-baseline-rewrite

## 읽어야 할 파일

먼저 아래 파일들을 읽고 태스크 배경과 설계 의도를 파악하라:

- `docs/tasks/payment-order-redesign/prd.md`
- `docs/tasks/payment-order-redesign/architecture.md`
- `docs/tasks/payment-order-redesign/adr.md` (특히 ADR-1, ADR-2, ADR-3, ADR-5, ADR-10)
- `docs/tasks/payment-order-redesign/db-schema.md`
- `docs/testing-conventions.md`
- `docs/logging-conventions.md`
- `docs/exception-strategy.md`
- `src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `src/main/java/com/commerce/payment/domain/PaymentAttemptType.java`
- `src/main/java/com/commerce/payment/domain/PaymentAttemptStatus.java`
- `src/main/java/com/commerce/payment/domain/PaymentAttemptFailCode.java`
- `src/main/java/com/commerce/payment/domain/Payment.java`
- `src/main/java/com/commerce/payment/domain/PaymentStatus.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentAttemptRepository.java`
- `src/main/java/com/commerce/payment/application/PaymentReadyService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalAttemptService.java`
- `src/main/java/com/commerce/payment/application/PaymentCancellationAttemptService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`
- `src/main/java/com/commerce/payment/presentation/PaymentReadyController.java` (있다면; URL/Controller 위치 확인)
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/order/domain/Order.java`
- `src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `src/main/java/com/commerce/order/application/OrderQueryService.java`
- `src/main/resources/db/migration/V1__init.sql`
- `docs/adr.md` (ADR-010, ADR-014, ADR-015 섹션)

## 작업

이 step 은 *PaymentReservation 도메인 신설 + Payment 도메인 재정의 (RESERVE 빠짐) + 스키마 분리 + Order 정리 + 호출처 baseline + reserve URL rename* 을 한 번에 처리한다. 이 step 끝나면 빌드/테스트 통과해야 함.

### 1. 신규 `PaymentReservation` 도메인

경로: `src/main/java/com/commerce/payment/domain/PaymentReservation.java`

- 클래스명: `PaymentReservation`
- `@Entity @Table(name = "tbl_payment_reservation", uniqueConstraints = { ... })` — `uk_payment_reservation_merchant_pay_key`, `uk_payment_reservation_reserved_key`
- 필드:
  - `Long id` (`@Id @GeneratedValue(strategy = IDENTITY)`)
  - `Long orderId` (`@Column(name = "order_id", nullable = false)`)
  - `Long memberId` (`@Column(name = "member_id", nullable = false)`)
  - `PaymentProvider provider` (`@Enumerated(STRING)`, `@Column(nullable = false)`)
  - `String merchantPayKey` (`@Column(name = "merchant_pay_key", nullable = false, length = 64)`)
  - `int amount` (`@Column(nullable = false)`)
  - `PaymentReservationStatus status` (`@Enumerated(STRING)`, `@Column(nullable = false)`)
  - `LocalDateTime expiresAt` (`@Column(name = "expires_at", nullable = false)`)
  - `String reservedKey` (`@Column(name = "reserved_key", length = 96)`) — RESERVED 일 때만 set, USED 면 NULL (NULL 트릭)
  - BaseTimeEntity 의 `createdAt`, `updatedAt`
- 정적 팩토리:
  - `createReserved(Long orderId, Long memberId, PaymentProvider provider, String merchantPayKey, int amount, Duration ttl)`:
    - status=RESERVED, expiresAt=now+ttl, reservedKey=`"{orderId}:{provider.name()}"`
- 도메인 메서드:
  - `boolean isReusableFor(Long memberId, PaymentProvider provider, int amount, LocalDateTime now)`:
    - `status == RESERVED && expiresAt.isAfter(now) && this.memberId.equals(memberId) && this.provider == provider && this.amount == amount`
  - `void markUsed()`:
    - 선조건: `status == RESERVED` (위반 시 `PaymentException(PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED)`)
    - **status=USED + reservedKey=null *같은 메서드 호출 안에서* set** (NULL 트릭 캡슐화 핵심)

### 2. 신규 `PaymentReservationStatus` enum

경로: `src/main/java/com/commerce/payment/domain/PaymentReservationStatus.java`

- 값: `RESERVED`, `USED`

### 3. 신규 `PaymentReservationRepository` + adapter

- 경로: `src/main/java/com/commerce/payment/domain/repository/PaymentReservationRepository.java`
- 메서드:
  - `PaymentReservation save(PaymentReservation reservation)`
  - `Optional<PaymentReservation> findById(Long id)`
  - `Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey)` — redirect 역조회 entry
  - `Optional<PaymentReservation> findReusable(Long orderId, Long memberId, PaymentProvider provider, int amount, LocalDateTime now)` — 재사용 조건 5개 동시 필터
- JPA: `src/main/java/com/commerce/payment/infrastructure/JpaPaymentReservationRepository.java` (Spring Data interface)
- Adapter: `src/main/java/com/commerce/payment/infrastructure/PaymentReservationRepositoryAdapter.java` (`@Component` + Repository 인터페이스 구현)
- `findReusable` JPQL:
  ```java
  @Query("""
      select pr from PaymentReservation pr
      where pr.orderId = :orderId
        and pr.memberId = :memberId
        and pr.provider = :provider
        and pr.amount = :amount
        and pr.status = com.commerce.payment.domain.PaymentReservationStatus.RESERVED
        and pr.expiresAt > :now
      """)
  Optional<PaymentReservation> findReusable(...);
  ```

### 4. 기존 `PaymentAttempt` → `Payment` rename + 컬럼 정리

**rename 충돌 처리 순서가 중요**: (a) 기존 `Payment` (성공 결제 1:1) 폐기 → (b) `PaymentAttempt` → `Payment` 로 rename.

#### (a) 기존 `Payment` (성공 결제 1:1) 폐기

- `src/main/java/com/commerce/payment/domain/Payment.java` 삭제
- `src/main/java/com/commerce/payment/domain/PaymentStatus.java` (`COMPLETED`, `CANCELED` enum) 삭제 — 새 PaymentStatus 가 같은 자리 차지
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (성공 결제 repo) 삭제
- 옛 `JpaPaymentRepository`, `PaymentRepositoryAdapter` 삭제

#### (b) `PaymentAttempt` → `Payment` rename + 컬럼 정리

경로: `src/main/java/com/commerce/payment/domain/Payment.java` (rename + 신규 작성)

- 클래스명: `Payment` (의미: *PG 에 보낸 실제 요청 사건*, append-only)
- `@Entity @Table(name = "tbl_payment", uniqueConstraints = { ... })` — `uk_payment_merchant_pay_key_provider_pg_payment_id_type`, `uk_payment_approved_order_key`
- 기존 필드 유지: `id`, `merchantPayKey`, `pgPaymentId`, `amount`, `provider`, `type`, `status`, `failCode`, `failDetail`, `respondedAt`
- 신규 필드:
  - `Long orderId` (`@Column(name = "order_id", nullable = false)`)
  - `Long approvedOrderKey` (`@Column(name = "approved_order_key")`) — NULL 트릭 컬럼
- 변경 필드:
  - `pgPaymentId`: **NOT NULL 유지** (B안에서 RESERVE 가 빠지므로 항상 존재)
- 정적 팩토리:
  - `createRequested(PaymentReservation reservation, PaymentType type, String pgPaymentId)`:
    - type=APPROVE 또는 CANCEL, status=REQUESTED, amount/orderId/merchantPayKey/provider 는 Reservation 에서 복사
- 도메인 메서드:
  - `void succeed(LocalDateTime respondedAt)`:
    - 선조건: `status == REQUESTED`
    - status=SUCCEEDED, respondedAt set, failCode=null, failDetail=null
    - **type==APPROVE 인 경우 `approvedOrderKey=orderId` *같은 메서드 호출 안에서* set** (NULL 트릭 캡슐화 핵심)
  - `void fail(PaymentFailCode, String detail, LocalDateTime respondedAt)` — 기존 의미 유지
  - `void markUnknown(String detail, LocalDateTime respondedAt)`:
    - 선조건: `status == REQUESTED`
    - status=UNKNOWN, failDetail=detail, respondedAt set
  - `void verifyApprovedResponse(String responseMerchantPayKey, int responseTotalAmount)` — 기존 유지

### 5. `PaymentType` (현 `PaymentAttemptType` rename + 정리)

경로: `src/main/java/com/commerce/payment/domain/PaymentType.java`

- enum: `APPROVE`, `CANCEL` (RESERVE 없음 — B안에서 Reservation 으로 이동)

### 6. `PaymentStatus` (현 `PaymentAttemptStatus` rename + 정리, 옛 `PaymentStatus` 폐기 후 자리 차지)

경로: `src/main/java/com/commerce/payment/domain/PaymentStatus.java`

- enum: `REQUESTED`, `SUCCEEDED`, `FAILED`, `UNKNOWN` (RESERVED/EXPIRED 없음)

### 7. `PaymentFailCode` (현 `PaymentAttemptFailCode` rename)

경로: `src/main/java/com/commerce/payment/domain/PaymentFailCode.java`

- 기존 값 유지

### 8. `PaymentRepository` (현 `PaymentAttemptRepository` rename + 확장)

경로: `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (이름 재활용 — 옛 `PaymentRepository` 가 삭제됐으니 충돌 없음)

- 메서드:
  - `Optional<Payment> findApproveAttempt(String merchantPayKey, PaymentProvider provider, String pgPaymentId)` — 기존 유지
  - `Optional<Payment> findCancelAttempt(String merchantPayKey, PaymentProvider provider, String pgPaymentId)` — 기존 유지
  - `Payment save(Payment payment)` — 기존 유지
  - `Optional<Payment> findApproveSucceeded(String merchantPayKey)` — **신규**. APPROVE+SUCCEEDED 만
  - `boolean existsApproveSucceeded(String merchantPayKey)` — **신규**. ADR-014 의 `hasCompletedPayment` 구현용
  - `boolean existsUnknownByOrderId(Long orderId)` — **신규**. UNKNOWN 차단 검사용 (step 3 에서 활용, 본 step 에서 인터페이스만 정의)
- 어댑터 + JPA repo 함께 갱신 (`PaymentRepositoryAdapter`, `JpaPaymentRepository`). 옛 attempt 명 파일은 삭제

### 9. Flyway V6 마이그레이션

경로: `src/main/resources/db/migration/V6__redesign_payment_to_reservation_and_attempt.sql`

```sql
-- 1. 기존 tbl_payment (성공 결제 1:1) DROP (rename 충돌 회피)
DROP TABLE IF EXISTS tbl_payment;

-- 2. tbl_payment_attempt RENAME → tbl_payment
RENAME TABLE tbl_payment_attempt TO tbl_payment;

-- 3. tbl_payment 컬럼 추가
ALTER TABLE tbl_payment
  ADD COLUMN order_id BIGINT NOT NULL,
  ADD COLUMN approved_order_key BIGINT NULL;

-- 4. tbl_payment 인덱스 정리
ALTER TABLE tbl_payment DROP INDEX uk_payment_attempt_merchant_pay_key_provider_pg_payment_id_type;
ALTER TABLE tbl_payment ADD UNIQUE KEY uk_payment_merchant_pay_key_provider_pg_payment_id_type (merchant_pay_key, provider, pg_payment_id, type);
ALTER TABLE tbl_payment ADD UNIQUE KEY uk_payment_approved_order_key (approved_order_key);
ALTER TABLE tbl_payment ADD INDEX idx_payment_order (order_id);

-- 5. tbl_payment_reservation 생성
CREATE TABLE tbl_payment_reservation (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  order_id          BIGINT       NOT NULL,
  member_id         BIGINT       NOT NULL,
  provider          VARCHAR(32)  NOT NULL,
  merchant_pay_key  VARCHAR(64)  NOT NULL,
  amount            INT          NOT NULL,
  status            VARCHAR(32)  NOT NULL,
  expires_at        DATETIME(6)  NOT NULL,
  reserved_key      VARCHAR(96)  NULL,
  created_at        DATETIME(6)  NOT NULL,
  updated_at        DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_reservation_merchant_pay_key (merchant_pay_key),
  UNIQUE KEY uk_payment_reservation_reserved_key (reserved_key),
  KEY idx_reservation_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. tbl_order 에서 merchant_pay_key 제거
ALTER TABLE tbl_order DROP INDEX uk_order_merchant_pay_key;
ALTER TABLE tbl_order DROP COLUMN merchant_pay_key;
```

### 10. Order 정리

경로: `src/main/java/com/commerce/order/domain/Order.java`

- 필드 제거: `merchantPayKey`
- 메서드 제거: `assignMerchantPayKey(String)`
- `@Table` uniqueConstraints 에서 `uk_order_merchant_pay_key` 제거

경로: `src/main/java/com/commerce/order/domain/repository/OrderRepository.java`

- 메서드 제거: `findByMerchantPayKeyAndMemberId`, `findByMerchantPayKey`, `findByMerchantPayKeyForUpdate`

경로: `src/main/java/com/commerce/order/infrastructure/OrderRepositoryAdapter.java`, `JpaOrderRepository.java`

- 위 3 개 메서드 구현 제거

경로: `src/main/java/com/commerce/order/application/OrderQueryService.java`

- 메서드 제거: `getOrderByMerchantPayKeyAndMemberId(String, Long)`
- 클래스가 비어버리면 클래스 자체 삭제. 다른 호출처가 남아있으면 의미 있는 형태로 유지

### 11. ReservePaymentService (구 PaymentReadyService rename + baseline)

#### 클래스 rename

- `src/main/java/com/commerce/payment/application/PaymentReadyService.java` → `ReservePaymentService.java`
- 사유: CLAUDE.md 의 *유스케이스 단위* 형식 (`CreateOrderService`) 일치

#### baseline 흐름 (재사용/UNKNOWN 차단 정책은 step 2/3)

```java
@Transactional
public ReservePaymentResult reserve(ReservePaymentCommand command) {
    Order order = orderRepository.findByIdAndMemberIdWithItems(command.getOrderId(), command.getMemberId())
        .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

    order.checkPayable();

    LocalDateTime now = LocalDateTime.now();
    String merchantPayKey = "PAY-" + UlidGenerator.generate();
    PaymentReservation reservation = paymentReservationRepository.save(
        PaymentReservation.createReserved(
            order.getId(), command.getMemberId(), command.getProvider(),
            merchantPayKey, order.getTotalPrice(), Duration.ofMinutes(30)
        )
    );

    PaymentProviderProperties properties = propertiesResolver.resolve(command.getProvider());
    // (기존 productName, totalPayAmount, returnUrl 빌드 — 그대로 옮김)
    return ReservePaymentResult.builder()
        .merchantPayKey(reservation.getMerchantPayKey())
        .build();
}
```

- 만료 상수 `PAYMENT_RESERVATION_EXPIRY = Duration.ofMinutes(30)` 도입 (서비스 상수)
- 재사용/UNKNOWN 차단은 step 2/3 에서 보강

### 12. NaverPayApprovalService (baseline)

#### 진입 시 역조회 — Order → Reservation 으로 변경

```java
PaymentReservation reservation = paymentReservationRepository.findByMerchantPayKey(merchantPayKey)
    .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

if (!reservation.getMemberId().equals(memberId)) {
    throw new PaymentException(PaymentErrorCode.PAYMENT_MEMBER_MISMATCH);
}

Order order = orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)
    .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
```

#### baseline approve 흐름 (USED 멱등 응답 / UNKNOWN 차단 / 보상 path 는 step 3)

```java
Payment existing = paymentRepository.findApproveSucceeded(merchantPayKey).orElse(null);
if (existing != null) {
    return toResponse(existing);
}

reservation.markUsed();   // status=USED + reservedKey=NULL 같은 UPDATE 안에서
Payment attempt = paymentApprovalAttemptService.create(reservation, pgPaymentId);

// [트랜잭션 밖]
NaverPayApproveResult result = naverPayGateway.approve(pgPaymentId);

// [트랜잭션 안] - completeVerifiedApproval
attempt.verifyApprovedResponse(result.getMerchantPayKey(), result.getTotalAmount());
attempt.succeed(LocalDateTime.now());   // approvedOrderKey=orderId 같은 UPDATE
order.completePayment();
return toResponse(attempt);
```

- `OrderRepository.findByIdAndMemberId(Long orderId, Long memberId)` 시그니처 확인. 없으면 신설 (items fetch 안 필요한 케이스용)
- `paymentApprovalAttemptService.create(reservation, pgPaymentId)` — 시그니처 변경 (아래 13번)

### 13. PaymentApprovalService / PaymentApprovalAttemptService / PaymentCancellationAttemptService / PaymentApprovalCompensationService 갱신

#### `PaymentApprovalService`

- **삭제**: `completeApprovedPayment(merchantPayKey, provider, pgPaymentId, approvedAt)` — 의미가 `Payment.succeed()` 안에 흡수
- **삭제**: `findPaymentByMerchantPayKey(merchantPayKey)` — 호출처에서 `paymentRepository.findApproveSucceeded(merchantPayKey)` 직접 호출
- **유지**: `hasCompletedPayment(merchantPayKey)` — 내부 구현을 `paymentRepository.existsApproveSucceeded(merchantPayKey)` 로 갱신. ADR-014 의미 보존

#### `PaymentApprovalAttemptService`

- 시그니처 변경:
  - 기존 `getOrCreate(merchantPayKey, provider, pgPaymentId, amount)` → `create(PaymentReservation reservation, String pgPaymentId)`
  - 사유: Reservation 이 amount/provider/orderId/merchantPayKey 의 owner 이므로 Reservation 객체를 받는 게 자연
- 내부에서 `Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId)` 호출
- `failIfRequested(merchantPayKey, provider, pgPaymentId, failCode, detail, now)` 같은 보조 메서드는 유지 (step 3 의 보상 path 에서 사용)

#### `PaymentCancellationAttemptService`

- 이름 유지. 내부 entity 타입이 `PaymentAttempt` → `Payment` 로 바뀜
- 시그니처는 cancel 흐름이 구현 안 됐으므로 baseline 만 갱신 (실제 cancel 흐름은 후속 task)

#### `PaymentApprovalCompensationService`

- 내부 import 만 갱신 (`PaymentAttempt` → `Payment`, `PaymentAttemptFailCode` → `PaymentFailCode`). 보상 path 추가는 step 3

### 14. Presentation rename — `/payments/ready` → `/payments/reserve`

- Controller URL: `POST /payments/ready` → **`POST /payments/reserve`**
- Controller class: `PaymentReadyController` → `ReservePaymentController` (있다면)
- DTO rename:
  - `PaymentReadyRequest` → `ReservePaymentRequest`
  - `PaymentReadyResponse` → `ReservePaymentResponse`
  - 응답 본문 구조는 동일 (필드 이름/구조 유지)
- 부수: import / 테스트 / properties / 주석 등 ready→reserve 일관성 확보 (단 *결제 영역의 ready* 만 — `Order.checkPayable` 같은 *다른 의미의 ready* 는 건드리지 마라)
- 검색 명령: `rg "/payments/ready" src/main/java src/test/java`, `rg "PaymentReady" src/main/java src/test/java`

### 15. 도메인 테스트

> **테스트 작성 체크리스트** (모든 step 공통)
> - 통합/동시성은 Testcontainers MySQL (H2 금지 — NULL 트릭/unique 예외 타입 차이)
> - 동시성 테스트는 `@Tag("docker")` + `@Tag("concurrency")` 둘 다
> - unique 위반은 `DataIntegrityViolationException` 또는 자손으로 단언 (드라이버별 구체 타입 의존 금지)
> - 도메인 캡슐화 단언은 순수 도메인 단위 테스트로 (DB 없이)
> - 멱등 재요청 테스트는 *부작용 0회* (PG 호출 0, 새 행 0) 도 함께 단언
> - "에러 아닌 멱등 응답" 케이스는 *예외가 안 난다* 는 것 자체를 단언

#### `PaymentReservationTest` (신규)

경로: `src/test/java/com/commerce/payment/domain/PaymentReservationTest.java`

- `@DisplayName` 한국어 + 메서드명 `행위_조건_결과` 형식
- 시나리오:
  - `createReserved_setsAllFields_returnsReservedReservation` — status=RESERVED, reservedKey=`"{orderId}:{provider}"`, expiresAt=now+ttl
  - `isReusableFor_whenSameOrderProviderMemberAmount_returnsTrue`
  - `isReusableFor_whenDifferentProvider_returnsFalse`
  - `isReusableFor_whenDifferentMember_returnsFalse`
  - `isReusableFor_whenDifferentAmount_returnsFalse`
  - `isReusableFor_whenExpired_returnsFalse`
  - `isReusableFor_whenUsed_returnsFalse`
  - `markUsed_changesStatusToUsed_andClearsReservedKey` — **한 메서드 호출에서 두 필드 동시 변경 검증**
  - `markUsed_whenAlreadyUsed_throwsException`

#### `PaymentTest` (현 `PaymentAttemptTest` 갱신)

경로: `src/test/java/com/commerce/payment/domain/PaymentTest.java`

- 시나리오:
  - `createRequested_fromReservation_setsAllFields` — status=REQUESTED, type=APPROVE/CANCEL, amount/orderId/provider/merchantPayKey 가 Reservation 에서 복사됨
  - `succeed_whenTypeApprove_setsStatusAndApprovedOrderKey` — **status=SUCCEEDED + approvedOrderKey=orderId 한 메서드 호출에서 동시 set 검증**
  - `succeed_whenTypeCancel_setsStatusAndKeepsApprovedOrderKeyNull`
  - `succeed_whenStatusNotRequested_throwsException`
  - `fail_setsStatusAndFailCode`
  - `fail_keepsApprovedOrderKeyNull` — 실패 행의 NULL 트릭 컬럼이 NULL 유지 (unique 제약 안 받음)
  - `markUnknown_setsStatusAndFailDetail`
  - `markUnknown_keepsApprovedOrderKeyNull`
  - `markUnknown_whenStatusNotRequested_throwsException`
  - `verifyApprovedResponse_*` (기존 검증 보존)

### 16. 기존 테스트 갱신

- `rg "PaymentAttempt" src/test/java` 결과의 모든 테스트 갱신 (rename 반영)
- `rg "merchantPayKey" src/test/java` 결과 중 Order 기반 흐름 → Reservation 기반으로 갱신
- `rg "PaymentReady" src/test/java` → `ReservePayment` 로 갱신
- `PaymentApprovalServiceTest` — 삭제된 메서드 테스트 제거, `hasCompletedPayment` 구현 변경 검증
- `PaymentApprovalAttemptServiceTest` — `create` 의 *동일 키 (merchantPayKey, provider, pgPaymentId) 재호출 시 기존 REQUESTED 행 재사용 + save 호출 0회* 시나리오 추가 (같은 시도 중복 기록 방지의 application 멱등)
- `OrderRepositoryAdapterTest` — `findByMerchantPayKey*` 테스트 삭제
- `NaverPayApprovalServiceTest` — Reservation 기반 역조회 / memberId 검증으로 갱신

### 17. PaymentReservation TestSupport 추가 (testing-conventions 준수)

경로: `src/test/java/com/commerce/payment/infrastructure/persistence/support/PaymentReservationPersistenceTestSupport.java`

- `PersistenceTestSupport` 인터페이스 구현
- 도메인별 cleanup + 테스트 데이터 헬퍼 제공 (`save`, `saveAndFlush`, `findById`, `count`)
- `CleanupOrder` enum 에 추가: PAYMENT_RESERVATION 항목 (PAYMENT(10) 와 같은 가중치 또는 그 앞)
  - 권장: `PAYMENT_RESERVATION(10)` (Reservation 도 결제 영역), `PAYMENT(11)` 로 PAYMENT 가중치 +1
  - 또는 `PaymentReservation` 을 PAYMENT 안에서 함께 cleanup (현재 `PaymentPersistenceTestSupport` 가 reservation 도 같이 비우게)
  - 어느 쪽이든 *FK 안전 삭제 순서* 유지하면 OK (cross-aggregate FK 없으므로 사실상 어떤 순서로 비워도 됨)

### 18. commit 분리 가이드 (commit agent 가 자동 분리)

이 step 의 변경은 목적별로 다음 단위로 commit 분리한다:

1. `refactor: Payment 도메인을 PG 사건 단위로 재정의한다` — PaymentAttempt → Payment rename, enum 정리, 기존 Payment (성공 결제) 폐기
2. `feat: PaymentReservation 도메인을 신설한다` — Reservation 엔티티 + enum + Repository + Adapter + TestSupport + CleanupOrder 추가
3. `chore: Flyway V6 마이그레이션을 추가한다` — DDL 만
4. `refactor: Order 에서 merchantPayKey 책임을 제거한다`
5. `refactor: 결제 서비스를 Reservation 기반 흐름으로 재배선한다` — ReservePaymentService rename + NaverPayApprovalService 역조회 변경 + 영향 서비스 일괄
6. `refactor: 결제 ready 엔드포인트를 reserve 로 rename 한다` — Controller URL/이름, DTO rename
7. `test: 새 Payment 와 PaymentReservation 도메인 테스트를 추가한다`

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드 모두 통과 확인
2. 식별자 누락 검색 (전수):
   ```bash
   rg "PaymentAttempt" src/main/java src/test/java
   rg "merchantPayKey" src/main/java src/test/java
   rg "assignMerchantPayKey" src/main/java src/test/java
   rg "getOrderByMerchantPayKey" src/main/java src/test/java
   rg "findPaymentByMerchantPayKey" src/main/java src/test/java
   rg "completeApprovedPayment" src/main/java src/test/java
   rg "PaymentReady" src/main/java src/test/java
   rg "/payments/ready" src/main/java src/test/java
   ```
   - `PaymentAttempt` 잔존 0건
   - `merchantPayKey` 는 `Payment` / `PaymentReservation` / NaverPay DTO / properties 에만 남고 Order 관련 0건
   - 폐기 메서드명 (`assignMerchantPayKey`, `getOrderByMerchantPayKey`, `findPaymentByMerchantPayKey`, `completeApprovedPayment`) 0건
   - `PaymentReady`, `/payments/ready` 잔존 0건 (모두 `ReservePayment`, `/payments/reserve` 로 이동)
3. 도메인 캡슐화 확인 (코드 + 테스트로 이중 단언):
   - `Payment.succeed()` 메서드 안에서 `status` 와 `approvedOrderKey` 가 같은 메서드 호출에서 set 되는지
   - `PaymentReservation.markUsed()` 메서드 안에서 `status` 와 `reservedKey` 가 같은 메서드 호출에서 set 되는지
   - 위 두 검증을 `PaymentTest`, `PaymentReservationTest` 가 단언으로 박아두는지
4. Flyway V6 적용 후 schema (Testcontainers 환경에서 확인):
   - `tbl_payment` 의 새 컬럼 (`order_id NOT NULL`, `approved_order_key NULL`, `pg_payment_id NOT NULL 유지`) 존재
   - `tbl_payment` 의 인덱스 (`uk_payment_approved_order_key`, `uk_payment_merchant_pay_key_provider_pg_payment_id_type`, `idx_payment_order`) 존재
   - `tbl_payment_reservation` 테이블 존재 + 인덱스 (`uk_payment_reservation_merchant_pay_key`, `uk_payment_reservation_reserved_key`, `idx_reservation_order`)
   - `tbl_order` 에서 `merchant_pay_key` 컬럼 / `uk_order_merchant_pay_key` 부재
5. 결과에 따라 step 상태 갱신

## 금지사항

- `approvedOrderKey` setter / `reservedKey` setter 를 외부에 노출하지 마라. 이유: status 와의 동시 set 캡슐화가 깨지면 NULL 트릭 unique 의 정합성이 무너진다 (ADR-3).
- 기존 `Payment` (성공 결제 1:1) 코드의 임시 보존을 위해 dual-write 하지 마라. 이유: 의도가 *폐기* 다. 보존하면 새 모델과 옛 모델이 공존해 review 비용이 폭증한다.
- `Order` 에 결제 관련 필드/메서드를 다시 추가하지 마라. 이유: ADR-2 의 도메인 분리 원칙 위반.
- reserve 단계에서 NaverPay API 를 호출하지 마라. 이유: 본 구조는 *서버가 키 발급 + 결제창 정보만 반환, PG 사전 통신 없음*. ADR-5 기록.
- `PaymentReservation` 에 `expire()` 또는 `markExpired()` 메서드를 추가하지 마라. 이유: ADR-5 의 *만료는 마킹 없이 필터로만* 정책. EXPIRED 상태 제거가 B안의 단순화 포인트.
- 머지된 task 폴더 (`docs/tasks/payment-attempt-*`, `docs/tasks/payment-compensation-*` 등) 의 문서를 수정하지 마라. 이유: CLAUDE.md 의 *완료된 task 불변 원칙*. 변경은 step 4 의 루트 docs 동기화로만 표현한다.
- 새 `PaymentRepository` 에 *옛 성공 결제 의미* 의 메서드를 남기지 마라. 이유: 새 의미 (PG 사건 단위) 와 옛 의미 (성공 결제) 가 한 인터페이스에 섞이면 호출처가 어느 의미인지 헷갈린다.
- 기존 테스트를 통과시키기 위해 새 모델의 캡슐화를 우회 (setter / reflection) 하지 마라. 이유: 캡슐화가 ADR-3 의 핵심 강제 수단이다.
- 동시성 (Testcontainers MySQL) 통합 테스트의 unique 검증을 이 step 에 추가하지 마라. 이유: 동시성 시나리오는 step 2 (`uk_payment_reservation_reserved_key`) / step 3 (`uk_payment_approved_order_key`) 에서 다룬다. 이 step 은 단위 도메인 테스트 + 빌드 통과 보장만.
- `Application` 계층에서 `log.info/warn/error` 를 직접 남기지 마라 — *유스케이스 시작/완료 도메인 이벤트* INFO 외에는 `GlobalExceptionHandler` 일괄 처리 (`logging-conventions.md` §3). 도메인 계층은 로그 0건.
