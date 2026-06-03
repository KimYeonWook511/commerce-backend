# 태스크 아키텍처

## 개요

- Payment 도메인의 JPA Entity 간 cross-aggregate association 을 해제한다.
- 변경 대상은 JPA 매핑 레벨이며, DB schema (컬럼·FK·unique) 와 응답 계약은 그대로 유지한다.
- 선행 sub-PR (`stock-jpa-association-decouple`, `order-jpa-association-decouple`) 의 패턴과 메타 원칙을 그대로 따른다. Payment 도메인은 fetch join 사용처가 없고 cross-aggregate 객체 참조 면적이 1건 (`Payment.order`) 으로 좁다.

## 변경 대상

### Domain 레이어

- `com.commerce.payment.domain.Payment`
  - `@OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id", ..., foreignKey = @ForeignKey(name = "fk_payment_order_id")) Order order` 필드 제거.
  - `@Column(name = "order_id", nullable = false) Long orderId` 필드 추가.
  - `@Table` 의 `uk_payment_order_id` unique constraint 는 그대로 유지 (컬럼 매핑 변경만).
  - 정적 팩토리: `Payment.createCompleted(Order, PaymentProvider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)` → `Payment.createCompleted(Long orderId, int amount, PaymentProvider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)`.
    - 기존: `order.getTotalPrice()` 를 도메인 내부에서 호출해 `amount` 채움.
    - 변경: `amount` 를 호출자가 명시적으로 전달 (외부 객체 의존 0).
  - 도메인 메서드 / 검증 로직 / `@Builder` 등 기존 동작은 유지한다.
- `com.commerce.payment.domain.PaymentAttempt`
  - 변경 없음. 이미 `merchantPayKey`, `pgPaymentId`, `provider` 기반이며 association 없음.

### Repository 레이어

- `com.commerce.payment.infrastructure.JpaPaymentRepository`
  - 변경 없음. derived query (`findByMerchantPayKey`, `existsByMerchantPayKeyAndStatus`) 만 사용 — JPQL fetch join 0건.

### Application 레이어

- `com.commerce.payment.application.PaymentApprovalService`
  - `completeApprovedPayment` 메서드에서 `Payment.createCompleted(order, provider, ...)` → `Payment.createCompleted(order.getId(), order.getTotalPrice(), provider, ...)` 로 호출부 갱신.
  - Order 조회 / `order.completePayment()` 호출 / log 메시지 등 다른 로직은 유지.
- `com.commerce.payment.application.PaymentReadyService`
  - 변경 없음 — 이미 선행 Order PR #200 에서 `productRepository.findAllById` batch composition 패턴으로 정리됨. Payment 객체 traversal 없음.
- `com.commerce.payment.naverpay.application.NaverPayApprovalService`
  - 변경 없음 — Payment 객체에서 Order 를 traverse 하지 않음. `paymentApprovalService.completeApprovedPayment(...)` 호출만 함.
- `com.commerce.payment.application.PaymentApprovalCompensationService`
  - 변경 없음 — `PaymentAttempt.merchantPayKey` / `pgPaymentId` 기반 보상.

### Result DTO 변경

- 응답 DTO (NaverPayApproveResponse, PaymentReadyResult) 시그니처·필드 모두 유지. 본 sub-PR 의 정책 목적은 cross-aggregate ID 참조 통일이며, 응답 echo 정리는 별도 트랙.

### Test

- main code 의 `Payment.createCompleted(order, ...)` 호출부 1건 → 새 시그니처로 변경.
- test fixture 의 `Payment.createCompleted(order, ...)` 호출부 (`PaymentTest`, `PaymentApprovalServiceTest`, `PaymentRepositoryJpaAdapterTest`, `NaverPayApprovalServiceTest`, `NaverPayServiceIntegrationTest`) → 새 시그니처로 변경.
- `PaymentApprovalServiceTest.completeApprovedPayment_whenPaymentNotExists_createPayment` 의 `result.getOrder().getStatus()` assertion → Payment 도메인이 Order 를 traverse 하지 않도록 검증 방식 조정 (예: `orderRepository.findByMerchantPayKey(...)` 로 Order 를 별도 조회해 status 확인, 또는 verify(order).completePayment() 호출 검증으로 단순화).
- concurrency 태그 테스트의 Payment fixture 호출부 일괄 갱신.

## 설계 방향

### Cross-aggregate ID 참조

- ADR-020 의 cross-aggregate ID 참조 원칙을 Payment aggregate 에 적용.
- Payment 와 Order 는 별 aggregate 다. cascade / orphanRemoval 없고 lifecycle 결합도 약함 (Order 가 만료/취소되어도 Payment 이력은 남고, Payment 가 취소되어도 Order 이력은 남는다). Long ID 참조가 자연스럽다.
- PaymentAttempt 는 Payment 와 객체 참조가 없는 독립 entity. `merchantPayKey` / `pgPaymentId` 가 결제 시도 단위 식별자. 본 sub-PR 에서 PaymentAttempt 변경 없음.

### fetch join 대체 패턴 (Order PR #200 일반 원칙 인용)

- Payment 도메인은 `JpaPaymentRepository` 에 fetch join JPQL 이 없다. 본 sub-PR 에서 fetch join 대체 결정 사항 없음.
- Order PR #200 결정 2 에서 정립된 일반 원칙 ("same-aggregate fetch 유지 / cross-aggregate fetch 제거") 을 인용만 한다.

### 도메인 시그니처 — Long ID 전환

- Order PR #200 결정 3 의 Long ID 시그니처 패턴을 적용한다.
- `Payment.createCompleted` 기존 시그니처는 `Order` 객체를 받아 내부에서 `order.getTotalPrice()` 만 호출. 객체 의존을 강제하면서도 실제 사용 필드가 1개 (`totalPrice`).
- 새 시그니처: `createCompleted(Long orderId, int amount, ...)`. 호출처 (`PaymentApprovalService`) 가 같은 트랜잭션에서 Order 를 조회·잠그고 `completePayment()` 호출 + `getId()` / `getTotalPrice()` 추출 후 도메인에 전달. Order 로드는 어차피 필요하므로 추가 조회 0건.
- `OrderRepository` 인터페이스에 신규 검증 메서드 (`existsById` 류) 추가하지 않음 — 호출처가 객체 필드를 같은 트랜잭션에서 함께 쓰므로 신설 메서드 사용처 0건. Order PR #200 의 학습 (회수된 `existsById` 시도) 을 그대로 따른다.

### 응답 조립 패턴

- 본 sub-PR 에서 응답 DTO 외부 주입 패턴을 새로 도입하지 않는다.
- Payment 도메인 응답 (`NaverPayApproveResponse`) 은 Payment 객체 자체에서 추출 가능한 필드만 사용 (`pgPaymentId`, `status`). 외부 컨텍스트 주입 불필요.
- PaymentReadyResult 의 productName 외부 주입은 이미 선행 Order PR #200 에서 정립됨. 본 sub-PR 에서 추가 변경 없음.

## 데이터 흐름

### 결제 승인 완료 (`NaverPayApprovalService.approve` → `PaymentApprovalService.completeApprovedPayment`)

```
completeApprovedPayment(merchantPayKey, provider, pgPaymentId, approvedAt)
  Order order = orderRepository.findByMerchantPayKeyForUpdate(merchantPayKey)
      .orElseThrow(ORDER_NOT_FOUND)

  Payment existing = paymentRepository.findByMerchantPayKey(merchantPayKey)
      .map(p -> validateCompletedPaymentOrThrow(p, provider, pgPaymentId))
      .orElse(null)

  paymentApprovalAttemptService.succeed(merchantPayKey, provider, pgPaymentId, approvedAt)

  if (existing != null) return existing

  order.completePayment()

  Payment saved = paymentRepository.save(
      Payment.createCompleted(order.getId(), order.getTotalPrice(),
                              provider, merchantPayKey, pgPaymentId, approvedAt)
  )
  return saved
```

기존 흐름과 동일. 도메인 호출 시 `order` 객체 대신 `order.getId(), order.getTotalPrice()` 를 추출해 전달하는 것만 차이.

### 결제 보상 (`PaymentApprovalCompensationService`)

- 변경 없음. `PaymentAttempt` 의 merchantPayKey / pgPaymentId 만 사용. Payment 객체나 Order 객체 모두 traverse 하지 않음.

## 예외 및 실패 처리

- Order 미존재 → `OrderException(ORDER_NOT_FOUND)` 유지.
- Payment 중복 / 상태 불일치 → `PaymentException` 기존 흐름 유지.
- 동시성 / 보상 흐름 예외 (`@Version`, optimistic lock, PG cancel race 등) → 기존과 동일.
- DB unique / FK 위반은 본 태스크 변경 대상 아님 → 안전망 500 위임 (`ADR-011`).

## 테스트 포인트

- `Payment` 단위 테스트 — `createCompleted(Long, int, ...)` 시그니처 변경 후 도메인 메서드 동작 유지.
- `PaymentApprovalServiceTest` — Long ID 시그니처 호출 / Payment 멱등 흡수 동작 / 보상 호출 동작 유지. `.getOrder()` traversal assertion 제거 또는 Order 조회 기반으로 교체.
- `PaymentRepositoryJpaAdapterTest` (`docker` 태그) — Hibernate `validate` 통과 및 영속 / 조회 동작 유지.
- `NaverPayApprovalServiceTest`, `NaverPayServiceIntegrationTest` — Payment fixture 갱신 후 승인 흐름 / 보상 흐름 회귀 없음.
- concurrency 태그 — Payment 승인 멱등 race / cancel race 회귀 없음.
- `./gradlew test integrationTest` 통과 — Hibernate `validate` 통과 확인 포함.
