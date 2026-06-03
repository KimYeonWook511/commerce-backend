# 태스크 아키텍처

## 개요

- Order / OrderItem 도메인의 JPA Entity 간 cross-aggregate association 을 해제한다.
- 변경 대상은 JPA 매핑 레벨이며, DB schema (컬럼·FK) 와 응답 계약은 그대로 유지한다.
- 선행 sub-PR (`stock-jpa-association-decouple`) 의 패턴과 메타 원칙을 그대로 따르되, Order 도메인 특유의 **fetch join 사용처 대체 패턴**을 본 sub-PR 에서 처음 정립한다.

## 변경 대상

### Domain 레이어

- `com.commerce.order.domain.Order`
  - `@ManyToOne Member member` 제거.
  - `@Column(name = "member_id", nullable = false) Long memberId` 추가.
  - `Order.create(Member)` → `Order.create(Long memberId)` 정적 팩토리 시그니처 변경.
  - `order.addOrderItem(Product, int)` → `order.addOrderItem(Long productId, int quantity)` 로 시그니처 변경. 내부에서 `OrderItem.of(this, productId, quantity)` 호출.
  - `Order.orderItems` (`@OneToMany`) 는 same-aggregate 관계로 유지.
- `com.commerce.order.domain.OrderItem`
  - `@ManyToOne Product product` 제거.
  - `@Column(name = "product_id", nullable = false) Long productId` 추가.
  - `OrderItem.of(Order, Product, int)` → `OrderItem.of(Order, Long productId, int quantity)` 시그니처 변경.
  - `OrderItem.order` (`@ManyToOne` parent) 은 same-aggregate 관계로 유지.

### Repository 레이어

- `com.commerce.product.domain.repository.ProductRepository`
  - `boolean existsById(Long productId)` 메서드 신설.
- `com.commerce.product.infrastructure.ProductRepositoryAdapter`
  - 위 메서드 구현. JpaRepository 기본 `existsById` 위임 또는 derived query 사용.
- `com.commerce.order.infrastructure.JpaOrderRepository`
  - `findByIdAndMemberIdWithItems` — JPQL 에서 `join fetch o.member`, `join fetch oi.product` 제거. `join fetch o.orderItems` 만 유지. `where o.id = :id and o.memberId = :memberId` 로 컬럼 직접 비교.
  - `findByIdWithItems` — 동일하게 cross-aggregate fetch 제거. `join fetch o.orderItems` 만 유지.
  - `findByMerchantPayKeyAndMemberId` — `join o.member` 제거. `where o.merchantPayKey = :merchantPayKey and o.memberId = :memberId` 로 컬럼 직접 비교.

### Application 레이어

- `com.commerce.order.application.OrderConcurrencyService` 외 Order 생성 경로
  - `Order.create(member)` → `Order.create(memberId)`, `order.addOrderItem(product, qty)` → `order.addOrderItem(productId, qty)` 로 호출부 갱신.
  - product 존재 검증은 `productRepository.existsById(productId)` 로 효율화. 객체 로드가 필요한 사용처는 기존 `findById` 유지.
- `com.commerce.order.application.OrderCancelService`
  - `item.getProduct().getId()` → `item.getProductId()` 로 traversal 제거.
  - stock 복원 Map (`Map<Long, Integer>`) 구성을 OrderItem.productId 직접 사용으로 변경.
- `com.commerce.order.application.OrderExpirationService`
  - `orderItem.getProduct().getId()` → `orderItem.getProductId()` 치환.
- `com.commerce.payment.application.PaymentReadyService`
  - 결제창 노출용 productName 을 다음 패턴으로 조립:
    1. `findByIdAndMemberIdWithItems` 로 Order + OrderItems 조회.
    2. OrderItems 의 productId 목록을 모아 `productRepository.findAllById(productIds)` 로 batch 조회.
    3. `Map<Long, String>` productName 맵 생성.
    4. 응답 DTO 조립 시 productName 을 외부 주입 (`from(order, productNameMap)`).
  - 응답 필드 구성 자체는 그대로 유지.
- `com.commerce.order.application.OrderQueryService`
  - `findByMerchantPayKeyAndMemberId` 시그니처 유지. 반환된 Order 의 memberId 컬럼을 사용한다.

### Result DTO 변경

- PaymentReady 응답 DTO (productName 노출 대상) — 정적 팩토리 시그니처를 `from(Order order)` → `from(Order order, Map<Long, String> productNameByProductId)` 또는 그에 준하는 외부 주입 형태로 변경.
- Order/OrderItem 객체 traversal 에 의존하던 응답 매핑은 OrderItem.productId 와 외부 주입된 컨텍스트를 결합해 조립.
- 다른 Order 관련 응답 DTO (취소, 만료) 는 productId 자체가 OrderItem 컬럼에 있어 외부 주입 불필요.

### Test

- 모든 `Order.create(member)` 호출부 → `Order.create(member.getId())`.
- 모든 `order.addOrderItem(product, qty)` 호출부 → `order.addOrderItem(product.getId(), qty)`.
- 모든 `OrderItem.of(order, product, qty)` 호출부 → `OrderItem.of(order, product.getId(), qty)`.
- 도메인 unit test, repository slice test, application test, integrationTest 전반 영향.
- 다른 도메인 (payment, cart) 의 order fixture 도 같은 시그니처 갱신 (컴파일 의존으로 불가피).
- concurrency 태그 테스트 (`OrderConcurrencyServiceTest`, `OrderCreateServiceConcurrencyTest`, `CartConcurrencyTest` 등) 도 fixture 갱신 범위에 포함.

## 설계 방향

### Cross-aggregate ID 참조

- ADR-020 의 cross-aggregate ID 참조 원칙을 Order / OrderItem aggregate 에 적용.
- Order aggregate 의 root 는 Order 이고, OrderItem 은 child 다. Order ↔ OrderItem 의 object reference 는 same-aggregate 관계로 유지한다.
- Order → Member, OrderItem → Product 의 객체 참조는 cross-aggregate 이므로 Long ID 로 전환한다.

### fetch join 대체 패턴 — 사용처별 분석

선행 stock sub-PR 이 의도적으로 미루어 둔 결정. 본 sub-PR 에서 사용처별 분석으로 정립한다.

**기본 원칙**

- same-aggregate fetch join (`join fetch o.orderItems`) 은 유지한다. N+1 회피와 aggregate consistency 모두 충족.
- cross-aggregate fetch join (`join fetch o.member`, `join fetch oi.product`) 은 모두 제거한다. 필요한 정보는 (a) 컬럼에 이미 있거나 (b) application 이 batch 조회로 외부 주입.

**사용처별 결정**

| 사용처 | 호출 service | 필요한 cross-aggregate 데이터 | 대체 패턴 |
|---|---|---|---|
| `findByIdAndMemberIdWithItems` (1) | `PaymentReadyService` | productName | batch composition — productIds 모아 `ProductRepository.findAllById` 1회, 응답 DTO 외부 주입 |
| `findByIdAndMemberIdWithItems` (2) | `OrderCancelService` | productId (stock 복원) | OrderItem.productId 직접 사용. 추가 조회 불필요 |
| `findByIdWithItems` | `OrderExpirationService` | productId (stock 복원) | OrderItem.productId 직접 사용. 추가 조회 불필요 |
| `findByMerchantPayKeyAndMemberId` | `OrderQueryService` | 없음 (where 조건용 join) | `where o.memberId = :memberId` 컬럼 비교로 단순화 |

**왜 사용처별로 다른가**

- 데이터 필요 양상이 다르다. cancel/expiration 은 productId 만 있으면 충분하므로 추가 쿼리가 0개로 더 효율적이다. PaymentReady 는 productName 노출이 필요하므로 batch 1회를 추가한다.
- 단일 원칙 (전부 DTO projection 또는 전부 batch composition) 을 강제하면 cancel/expiration 에 불필요한 패턴을 도입하게 된다.

### 도메인 시그니처 — Long ID + existsById 검증

- `Order.create(Member)` / `addOrderItem(Product, int)` 는 객체 의존을 강제해 unit test fixture 가 Member/Product entity 를 모두 만들어야 했다.
- Long ID 시그니처 전환으로 도메인 invariant 가 ID 기준으로 명확해진다 (Order 의 identity 는 memberId, OrderItem 의 reference 는 productId).
- application 의 product 존재 검증은 `findById` 객체 로드가 필요 없는 경우 `existsById` 로 전환해 한 row 만 조회한다 (`SELECT 1 FROM tbl_product WHERE id = ?`). 객체가 필요한 사용처는 기존 `findById` 유지.

### 응답 조립 패턴 (선행 stock 패턴 확장)

- stock sub-PR 의 `StockHistoryResult.from(history, productId)` 외부 주입 패턴을 확장한다.
- PaymentReady 응답은 `from(order, productNameByProductId)` 시그니처로 OrderItem 컬럼 + 외부 컨텍스트를 application 이 의도적으로 조립한다.
- entity 객체 traversal (`orderItem.getProduct().getName()`) 같은 "domain 그래프 == 응답 모델" 결합을 끊는다. ADR-020 통증 #1 ("편한 탐색 오용") 해소.

## 데이터 흐름

### 결제 준비 (`POST /payments/ready`)

```
PaymentReadyService.ready(command)
  order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
      // JPQL: join fetch o.orderItems (only)
  productIds = order.getOrderItems().stream().map(OrderItem::getProductId).toList()
  productNames = productRepository.findAllById(productIds).stream()
      .collect(toMap(Product::getId, Product::getName))  // batch composition
  return PaymentReadyResult.from(order, productNames)    // 외부 주입
```

### 주문 취소 (`POST /orders/{orderId}/cancel`)

```
OrderCancelService.cancel(command)
  order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
  stockRestoreMap = order.getOrderItems().stream()
      .collect(toMap(OrderItem::getProductId, OrderItem::getQuantity))  // productId 직접 사용
  stockInventoryService.increaseBatch(stockRestoreMap)
  order.cancel()
```

### 주문 만료 배치 (`OrderExpirationService.expire`)

```
OrderExpirationService.expire(orderId)
  order = orderRepository.findByIdWithItems(orderId)
  stockRestoreMap = order.getOrderItems().stream()
      .collect(toMap(OrderItem::getProductId, OrderItem::getQuantity))
  stockInventoryService.increaseBatch(stockRestoreMap)
  order.expire()
```

### 주문 생성 (`OrderConcurrencyService` 외)

```
createOrder(command)
  productRepository.existsById(item.getProductId())  // 존재 검증만 (객체 로드 X)
      .orElseThrow(ProductException(PRODUCT_NOT_FOUND))
  Order order = Order.create(command.getMemberId())
  for item in command.getItems():
      order.addOrderItem(item.getProductId(), item.getQuantity())
  orderRepository.save(order)
```

## 예외 및 실패 처리

- Product 존재 검증 실패 (`existsById == false`) → `ProductException(PRODUCT_NOT_FOUND)` 유지.
- Member 존재 검증은 기존 흐름 유지 (인증/JWT 단계에서 보장).
- Order 미존재 / 권한 없음 → `OrderException` 기존 흐름 유지.
- 동시성 / 보상 흐름 예외 (`OrderConcurrencyService`, optimistic lock 등) → 기존과 동일.
- DB unique / FK 위반은 본 태스크 변경 대상 아님 → 안전망 500 위임 (`ADR-011`).

## 테스트 포인트

- `Order` / `OrderItem` 단위 테스트 — `create(Long)`, `addOrderItem(Long, int)` 시그니처 변경 후 도메인 메서드 동작 유지.
- `JpaOrderRepository` slice/integration test — JPQL 변경 후 동일 결과 조회.
- `PaymentReadyServiceTest` — batch 조회 + 외부 주입 패턴 동작. productName 누락 없음.
- `OrderCancelServiceTest`, `OrderExpirationServiceTest` — productId 직접 사용으로 stock 복원 Map 동일 구성.
- `OrderConcurrencyServiceTest`, `OrderCreateServiceConcurrencyTest`, `OrderCreateServiceIdempotencyTest` — 비관적 락 / Optimistic lock retry / 멱등 흐름 회귀 없음.
- 다른 도메인 테스트 (`PaymentApprovalServiceTest`, `CartConcurrencyTest` 등) 의 order fixture 빌드 호환.
- `./gradlew test integrationTest` 통과 — Hibernate `validate` 통과 확인 포함.
