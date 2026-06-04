# Step 2: reservation-reuse-and-merchant-key-lookup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 step 1 의 결과를 파악하라:

- `docs/tasks/payment-order-redesign/prd.md`
- `docs/tasks/payment-order-redesign/architecture.md`
- `docs/tasks/payment-order-redesign/adr.md` (ADR-3 NULL 트릭, ADR-5 reserve 흐름)
- `docs/testing-conventions.md` (특히 *동시성 테스트 작성 규칙* 섹션)
- `src/main/java/com/commerce/payment/domain/PaymentReservation.java` (step 1 에서 생성)
- `src/main/java/com/commerce/payment/domain/repository/PaymentReservationRepository.java`
- `src/main/java/com/commerce/payment/application/ReservePaymentService.java` (step 1 baseline)
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` (step 1 baseline)

## 작업

step 1 에서 Reservation *생성 + 역조회 entry* 까지 baseline 처리됐다. 이 step 은 *재사용 + amount mismatch + 동시 따닥 차단* 의 정책 보강 + `findReusable` JPQL 다듬기.

### 1. `ReservePaymentService` 재사용 흐름 보강

경로: `src/main/java/com/commerce/payment/application/ReservePaymentService.java`

step 1 의 *항상 새 발급* 흐름을 *재사용 우선* 으로 갱신:

```java
@Transactional
public ReservePaymentResult reserve(ReservePaymentCommand command) {
    Order order = orderRepository.findByIdAndMemberIdWithItems(command.getOrderId(), command.getMemberId())
        .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

    order.checkPayable();
    // UNKNOWN 차단은 step 3 에서 추가

    LocalDateTime now = LocalDateTime.now();
    PaymentReservation reservation = paymentReservationRepository
        .findReusable(order.getId(), command.getMemberId(), command.getProvider(), order.getTotalPrice(), now)
        .orElseGet(() -> createNewReservation(order, command, now));

    PaymentProviderProperties properties = propertiesResolver.resolve(command.getProvider());
    return ReservePaymentResult.builder()
        .merchantPayKey(reservation.getMerchantPayKey())
        // (기존 productName, totalPayAmount, returnUrl 빌드 — 그대로)
        .build();
}

private PaymentReservation createNewReservation(Order order, ReservePaymentCommand command, LocalDateTime now) {
    String merchantPayKey = "PAY-" + UlidGenerator.generate();
    return paymentReservationRepository.save(
        PaymentReservation.createReserved(
            order.getId(), command.getMemberId(), command.getProvider(),
            merchantPayKey, order.getTotalPrice(), PAYMENT_RESERVATION_EXPIRY
        )
    );
}

private static final Duration PAYMENT_RESERVATION_EXPIRY = Duration.ofMinutes(30);
```

- `findReusable` 5 조건 (orderId / memberId / provider / amount / expiresAt) 모두 일치할 때만 재사용
- amount mismatch 는 *재사용 안 됨* → 자동으로 새 Reservation 발급 (ADR-5 의 amount 변경 = 새 Reservation 정책)
- 기존 RESERVED 행은 그대로 둠 (마킹/expire 메서드 없음 — ADR-5 의 *만료 마킹 안 함* 정책)

### 2. `findReusable` 구현 (`PaymentReservationRepository`)

step 1 에서 인터페이스 + JPQL 정의됨. 본 step 에서는 *5 조건 일관성* + *인덱스 활용* 확인:

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
Optional<PaymentReservation> findReusable(@Param("orderId") Long orderId,
                                           @Param("memberId") Long memberId,
                                           @Param("provider") PaymentProvider provider,
                                           @Param("amount") int amount,
                                           @Param("now") LocalDateTime now);
```

- 인덱스 활용: `idx_reservation_order(order_id)` 가 filter 의 출발점
- order 하나에 RESERVED 가 여러 개 (다른 provider 등) 있을 수 있으므로 *복수 후보 중 1 개* 선택. 결과 0 또는 1 보장은 `uk_payment_reservation_reserved_key` 가 `(order, provider)` 단위 RESERVED 유일성 강제로 자연 보장

### 3. `findByMerchantPayKey` redirect 역조회 (step 1 인터페이스 정의 → 본 step 에서 활용 확인)

```java
@Query("""
    select pr from PaymentReservation pr
    where pr.merchantPayKey = :merchantPayKey
    """)
Optional<PaymentReservation> findByMerchantPayKey(@Param("merchantPayKey") String merchantPayKey);
```

- USED 상태의 Reservation 도 조회됨 (step 3 의 멱등 응답 흡수에 필요)
- `uk_payment_reservation_merchant_pay_key` unique 가 결과 0 또는 1 보장

> **테스트 작성 체크리스트**: step 1 의 §15 참조. 핵심: Testcontainers MySQL (H2 금지) / 동시성은 `@Tag("docker")` + `@Tag("concurrency")` / unique 위반은 `DataIntegrityViolationException` 또는 자손 / 멱등 재요청 *부작용 0회* (PG 호출 0, 새 행 0) 함께 단언.

### 4. `uk_payment_reservation_reserved_key` 동시 따닥 차단 통합 테스트

경로: `src/test/java/com/commerce/payment/infrastructure/PaymentReservationRepositoryConcurrencyTest.java`

- `@Tag("docker")` + `@Tag("concurrency")` 둘 다 (testing-conventions §태그 규칙 — 환경 축 + 격리 축)
- Testcontainers MySQL 사용 (`TestcontainersSupport.registerMySql(registry)`)
- `tearDown` 에서 `PaymentReservationPersistenceTestSupport.deleteAllInBatch(...)` (`@Transactional` 금지)
- 동시성 컨벤션 (testing-conventions §동시성 테스트 작성 규칙) 준수 — *불변식 단언 패턴*:
  - N thread 가 같은 (orderId, provider, memberId, amount) 로 `PaymentReservation.createReserved` 동시 save
  - `CountDownLatch` 로 *동시 시작 / 종료 대기* (속도 맞추기 X)
  - 모든 thread 종료 후 invariant 단언: *DB 에 RESERVED 행이 정확히 1 개*, *나머지는 `DataIntegrityViolationException` 류*
- 메서드명: `createReserved_whenConcurrent_onlyOneSucceedsAndOthersFailUniqueViolation`
- `@DisplayName`: "같은 (주문, 수단, 회원, 금액) 으로 reserve 가 동시에 들어와도 RESERVED 는 정확히 1개만 살아남는다"

### 5. `findReusable` JPA 슬라이스 테스트

경로: `src/test/java/com/commerce/payment/infrastructure/PaymentReservationRepositoryJpaAdapterTest.java`

- `@DataJpaTest` 또는 `@DataJpaTest` + Adapter 등록
- 시나리오 (모두 `@DisplayName` + `행위_조건_결과` 메서드명):
  - `findReusable_whenAllConditionsMatch_returnsReservation`
  - `findReusable_whenProviderDiffers_returnsEmpty`
  - `findReusable_whenMemberDiffers_returnsEmpty`
  - `findReusable_whenAmountDiffers_returnsEmpty`
  - `findReusable_whenExpired_returnsEmpty` — `expires_at < now` 인 RESERVED 는 결과에서 제외
  - `findReusable_whenUsed_returnsEmpty`
- `findByMerchantPayKey` 도 검증:
  - `findByMerchantPayKey_whenReservedExists_returnsReservation`
  - `findByMerchantPayKey_whenUsedExists_returnsReservation` — USED 상태도 조회됨 (step 3 멱등 흡수용)
  - `findByMerchantPayKey_whenNoReservation_returnsEmpty`

### 6. `ReservePaymentService` 단위 테스트

경로: `src/test/java/com/commerce/payment/application/ReservePaymentServiceTest.java` (step 1 의 갱신본 보강)

- `@ExtendWith(MockitoExtension.class)`, `@Mock` (`PaymentReservationRepository`, `OrderRepository`, ...)
- 시나리오:
  - `reserve_whenReusableReservationExists_reusesIt` — `findReusable` 가 Reservation 반환 → 그 행의 merchantPayKey 반환, `save` 호출 0번
  - `reserve_whenNoReusable_createsNewReservation` — `findReusable` empty → `save` 1번 + 결과 반환
  - `reserve_whenAmountChanged_createsNewReservation` — order.totalPrice 가 바뀌면 `findReusable` 가 빈 결과 → 새 Reservation 발급. 기존 RESERVED 행은 *그대로 둠* (expire 호출 X)
  - `reserve_whenDifferentProvider_createsNewReservation` — 다른 provider 로 reserve → 새 Reservation 발급 (기존 RESERVED 와 공존 가능)
  - `reserve_whenOrderNotPayable_throwsException` — `Order.checkPayable()` 위반
  - `reserve_whenCalledTwiceSequentially_returnsSameMerchantPayKey` — 같은 `(orderId, memberId, provider, amount)` 로 순차 두 번 호출 → 두 번 다 같은 merchantPayKey 반환, 두 번째 `save` 호출 0회 (멱등 + 부작용 0회)

### 7. commit 분리

1. `feat: 결제 reserve 가 유효 Reservation 을 재사용한다` — ReservePaymentService 재사용 흐름 + findReusable 활용
2. `test: Reservation 재사용 + 동시 따닥 차단 테스트를 추가한다` — JPA 슬라이스 + Testcontainers 동시성

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

(`concurrencyTest` 는 CI 미포함 / 수동. 본 step 의 변경이 동시성 인덱스를 직접 다루므로 영향 범위에 맞춰 수동 실행)

## 검증 절차

1. 위 커맨드 모두 통과
2. `ReservePaymentService.reserve` 안에서 *직접 `merchantPayKey` 발급* 경로가 `createNewReservation` 한 곳으로 통합됐는지 확인
3. `findReusable` 의 조건 5 가지 (orderId / memberId / provider / amount / status=RESERVED + expiresAt>now) 가 JPQL 에 모두 반영됐는지 확인
4. 동시성 통합 테스트가 *진짜* MySQL 컨테이너로 unique 위반을 재현하는지 확인 (H2 가 아님)
5. amount mismatch 시나리오에서 기존 RESERVED 행이 *그대로 남아있는지* (expire/markExpired 호출 0건) 확인 — ADR-5 정책
6. 결과에 따라 step 상태 갱신

## 금지사항

- `findReusable` 을 `FOR UPDATE` 로 잠그지 마라. 이유: ADR-9 의 *없는 행 조회 FOR UPDATE → INSERT* 패턴은 InnoDB gap lock 으로 옆 범위 INSERT 까지 막는다. 동시 따닥 차단은 `uk_payment_reservation_reserved_key` unique 가 담당한다.
- `expiresAt` 을 클라이언트 입력으로 받지 마라. 이유: 만료 시각은 서버 정책 (상수) 으로만 결정한다. 클라이언트 신뢰 금지.
- `PaymentReservation` 에 `expire()` / `markExpired()` 메서드를 추가하지 마라. 이유: ADR-5 의 박제 자동 복구 정신. 만료는 *판단 시 expiresAt 필터* 만으로 충분. EXPIRED 상태 자체가 없음.
- 만료된 Reservation 의 batch sweep 을 이 step 에 추가하지 마라. 이유: 후속 task (운영 데이터 누적 시점에 결정).
- amount mismatch 시 기존 RESERVED 행을 *삭제 / 마킹* 하지 마라. 이유: ADR-5 의 *그대로 두고 새 Reservation 발급* 정책. 박제처럼 보이지만 expires_at 으로 자동 회수.
- 동시성 테스트에서 `Thread.sleep` 으로 타이밍 맞추거나 latch 로 *속도* 맞추지 마라. 이유: testing-conventions §동시성 *타이밍을 운에 맡기지 않는다*. latch 는 *동시 시작 / 종료 대기* 용도만.
- 동시성 테스트에서 *race 무대 컴포넌트의 분기 결과* 를 stub 하지 마라. 이유: race 의 무대를 가린 상태에서 다중 thread 만 돌리는 건 동시성 검증이 아니다.
- 통합 테스트에 `@Transactional` 을 쓰지 마라. 이유: AFTER_COMMIT 동작 검증 불가 (testing-conventions §테스트 격리 원칙).
- `PAYMENT_RESERVATION_EXPIRY` 를 `Order` 도메인이 결정하게 하지 마라. 이유: 결제 도메인 정책.
