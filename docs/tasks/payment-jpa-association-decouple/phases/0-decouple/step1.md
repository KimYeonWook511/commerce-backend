# Step 1: payment-association-decouple

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-jpa-association-decouple/prd.md`
- `/docs/tasks/payment-jpa-association-decouple/architecture.md`
- `/docs/tasks/payment-jpa-association-decouple/adr.md`
- `/docs/tasks/payment-jpa-association-decouple/api-spec.md`
- `/docs/tasks/payment-jpa-association-decouple/db-schema.md`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/adr.md` (ADR-020 — 신규 도메인 cross-aggregate ID 참조, ADR-011 — find-first 패턴)
- `/docs/architecture.md`
- `/docs/tasks/stock-jpa-association-decouple/adr.md` (series 메타 원칙 / 외부 주입 패턴 최초 정립)
- `/docs/tasks/stock-jpa-association-decouple/retrospective.md`
- `/docs/tasks/order-jpa-association-decouple/adr.md` (fetch join 대체 일반 원칙 / Long ID 시그니처 패턴)
- `/docs/tasks/order-jpa-association-decouple/retrospective.md`

현재 코드 구조를 파악하기 위해 아래 파일도 읽는다.

- `/src/main/java/com/commerce/payment/domain/Payment.java`
- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `/src/main/java/com/commerce/payment/infrastructure/JpaPaymentRepository.java`
- `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `/src/main/java/com/commerce/payment/application/PaymentReadyService.java`
- `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `/src/main/java/com/commerce/order/domain/Order.java`

## 작업

Payment 도메인의 JPA cross-aggregate association 을 해제하고 `Long` ID 필드로 전환한다. `Payment.createCompleted` 시그니처를 Long ID + amount 명시 인자로 전환한다. ADR-020 후속 트랙의 마지막 sub-PR.

### 도메인 변경

- `Payment`
  - `@OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payment_order_id")) Order order` 필드 제거.
  - `@Column(name = "order_id", nullable = false) Long orderId` 필드 추가.
  - `@Table` 의 `uk_payment_order_id` unique constraint 매핑은 그대로 유지.
  - 정적 팩토리:
    - 기존: `Payment.createCompleted(Order order, PaymentProvider provider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)` — 내부에서 `order.getTotalPrice()` 호출해 `amount` 채움.
    - 변경: `Payment.createCompleted(Long orderId, int amount, PaymentProvider provider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)` — `amount` 를 호출자가 명시적으로 전달.
  - `@Builder` private constructor 인자도 `Order order` → `Long orderId` 로 변경. 빌더 시그니처 사용처가 정적 팩토리 내부 외에는 없는지 grep 으로 확인.
  - 기존 `@Version`(없음 — Payment 는 optimistic lock 사용 안 함), `BaseTimeEntity`, 도메인 메서드, exception 흐름은 그대로 유지한다.
- `PaymentAttempt`
  - 변경 없음. 이미 association 없음.

### Repository 레이어

- `JpaPaymentRepository`, `PaymentRepositoryAdapter`, `PaymentRepository` (port interface)
  - 변경 없음. derived query (`findByMerchantPayKey`, `existsByMerchantPayKeyAndStatus`) 만 사용 — JPQL fetch join 0건.

### Application 변경

- `PaymentApprovalService.completeApprovedPayment`
  - 기존:
    ```java
    Payment savedPayment = paymentRepository.save(
        Payment.createCompleted(order, provider, merchantPayKey, pgPaymentId, approvedAt)
    );
    ```
  - 변경:
    ```java
    Payment savedPayment = paymentRepository.save(
        Payment.createCompleted(order.getId(), order.getTotalPrice(), provider, merchantPayKey, pgPaymentId, approvedAt)
    );
    ```
  - Order 조회 / `order.completePayment()` 호출 / 멱등 흡수 로직 / log 메시지 등 다른 코드는 유지.
- `PaymentReadyService`
  - 변경 없음 — 이미 선행 Order PR #200 에서 `productRepository.findAllById` batch composition 패턴으로 정리됨.
- `NaverPayApprovalService`
  - 변경 없음 — Payment 객체에서 Order 를 traverse 하지 않음.
- `PaymentApprovalCompensationService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`
  - 변경 없음 — `PaymentAttempt.merchantPayKey` / `pgPaymentId` / `provider` 기반.

### Result DTO 변경

- 변경 없음. 본 sub-PR 의 정책 목적은 cross-aggregate ID 참조 통일이며, 응답 echo 정리는 별도 트랙 (ADR 결정 5).

### Test fixture 변경

- main code 의 `Payment.createCompleted(order, ...)` 호출부 1건 갱신 (`PaymentApprovalService.java:72`).
- test 의 `Payment.createCompleted(order, ...)` 호출부 일괄 갱신 — `order` → `order.getId(), order.getTotalPrice()` 인자 전개:
  - `/src/test/java/com/commerce/payment/domain/PaymentTest.java`
  - `/src/test/java/com/commerce/payment/application/PaymentApprovalServiceTest.java`
  - `/src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryJpaAdapterTest.java`
  - `/src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`
  - `/src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java`
- `PaymentApprovalServiceTest.completeApprovedPayment_whenPaymentNotExists_createPayment` 의 `assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.PAID)` 라인은 Payment 가 더 이상 Order 를 객체로 들지 않으므로 다음 중 하나로 교체:
  - mock `OrderRepository.findByMerchantPayKeyForUpdate` 가 반환한 Order 변수의 status 를 직접 assert (`assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID)`).
  - 또는 `then(order).should().completePayment()` 식의 호출 검증.
  - 본 sub-PR 의 변경 정신은 "Payment 가 Order 를 traverse 하지 않음" 이므로 assertion 도 Order 변수 직접 확인 방식을 선호한다.
- 그 외 `payment.getOrder()` traversal 사용처가 있으면 모두 갱신.
- 위 목록은 참고용이며, 실제 변경 시 추가 호출부가 컴파일 오류로 드러나면 모두 갱신한다.

### DB schema / Flyway

- 변경 없음. Flyway migration 파일 추가하지 않는다.
- DB FK 제약 (`fk_payment_order_id`), unique 제약 (`uk_payment_order_id`) 그대로 유지.

## 수정 가능 경로

- `src/main/java/com/commerce/payment/**`
- `src/test/java/com/commerce/payment/**`
- `docs/tasks/payment-jpa-association-decouple/**`

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `Payment` 에서 cross-aggregate `@OneToOne` import (Order 객체 의존) 가 제거됐는가? — `rg "import com.commerce.order.domain.Order" src/main/java/com/commerce/payment/domain` 결과 0건.
   - `Payment.orderId: Long` 컬럼 매핑이 추가되고 `@Table` 의 `uk_payment_order_id` 가 그대로 유지됐는가?
   - 객체 traversal 이 entity / application / test 코드에서 남아있지 않은가? — 아래 명령 결과 0건:
     - `rg "payment\.getOrder\(\)" src`
     - `rg "\.getOrder\(\)\." src/main/java/com/commerce/payment`
   - `PaymentApprovalService.completeApprovedPayment` 가 `Payment.createCompleted(order.getId(), order.getTotalPrice(), ...)` 형태로 호출하는가?
   - DB schema 변경 / Flyway V 파일 추가가 없는가? — `git diff src/main/resources/db/migration/` 결과 없음.
   - Hibernate `validate` 가 통과하는가? — `integrationTest` (Testcontainers MySQL) 가 schema 와 entity 매핑을 검증한다.
   - architecture.md 의 디렉토리 구조와 컨벤션을 따랐는가?
   - ADR-020 / ADR-011 등 상위 작업 규칙을 위반하지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `OrderRepository` 에 `existsById` 같은 신설 검증 메서드를 추가하지 마라. 이유: 호출처 (`PaymentApprovalService.completeApprovedPayment`) 가 같은 트랜잭션에서 Order 객체의 `getTotalPrice()`, `completePayment()` 를 함께 쓰므로 객체 로드가 어차피 필요하다. Order PR #200 의 회수된 시도 학습 (`docs/tasks/order-jpa-association-decouple/retrospective.md`) 을 따른다.
- Flyway V 파일을 추가하지 마라. 이유: 본 sub-PR series 의 메타 원칙은 schema 변경 0건이고, FK 제거는 별도 트랙이다.
- DB FK 제약 (`fk_payment_order_id`) 이나 unique 제약 (`uk_payment_order_id`) 을 제거하지 마라. 이유: 별도 트랙. JPA 매핑 차원에서만 association 해제한다. unique 제약은 `@Table` 매핑 그대로 유지한다.
- `@Table` 의 `uk_payment_order_id` 매핑을 제거하지 마라. 이유: Payment 1:1 Order 보장은 도메인 invariant 이며 코드 차원에서도 유지해야 한다.
- 응답 DTO (NaverPayApproveResponse, PaymentReadyResult) 의 필드 구성을 변경하지 마라. 이유: 본 sub-PR 의 정책 목적은 association 해제이지 응답 계약 정비가 아니다 (ADR 결정 5).
- PaymentAttempt 및 관련 service 를 변경하지 마라. 이유: 본 sub-PR 영향 범위 밖 (ADR 결정 6).
- `Payment.createCompleted` 의 `amount` 인자 대신 도메인 안에서 `orderRepository.findById(orderId).getTotalPrice()` 같은 추가 조회를 하지 마라. 이유: 도메인이 repository 에 의존하지 않는다 (의존성 방향 위반). amount 는 application 이 명시적으로 전달한다.
- 기존 테스트를 깨뜨리지 마라.
