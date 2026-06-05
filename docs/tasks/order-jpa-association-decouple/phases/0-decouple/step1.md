# Step 1: order-association-decouple

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/order-jpa-association-decouple/prd.md`
- `/docs/tasks/order-jpa-association-decouple/architecture.md`
- `/docs/tasks/order-jpa-association-decouple/adr.md`
- `/docs/tasks/order-jpa-association-decouple/api-spec.md`
- `/docs/tasks/order-jpa-association-decouple/db-schema.md`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/adr.md` (ADR-020 — 신규 도메인 cross-aggregate ID 참조, ADR-011 — find-first 패턴)
- `/docs/architecture.md`
- `/docs/tasks/stock-jpa-association-decouple/adr.md` (선행 sub-PR 의 메타 원칙 / 외부 주입 패턴)
- `/docs/tasks/stock-jpa-association-decouple/retrospective.md` (선행 sub-PR 회고에서 정립된 baseline)

현재 코드 구조를 파악하기 위해 아래 파일도 읽는다.

- `/src/main/java/com/commerce/order/domain/Order.java`
- `/src/main/java/com/commerce/order/domain/OrderItem.java`
- `/src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `/src/main/java/com/commerce/order/infrastructure/JpaOrderRepository.java`
- `/src/main/java/com/commerce/order/infrastructure/OrderRepositoryAdapter.java`
- `/src/main/java/com/commerce/order/application/OrderConcurrencyService.java`
- `/src/main/java/com/commerce/order/application/OrderCancelService.java`
- `/src/main/java/com/commerce/order/application/OrderExpirationService.java`
- `/src/main/java/com/commerce/order/application/OrderQueryService.java`
- `/src/main/java/com/commerce/payment/application/PaymentReadyService.java`
- `/src/main/java/com/commerce/product/domain/repository/ProductRepository.java`
- `/src/main/java/com/commerce/product/infrastructure/ProductRepositoryAdapter.java`

## 작업

Order / OrderItem 도메인의 JPA cross-aggregate association 을 해제하고 `Long` ID 필드로 전환한다. fetch join 대체 패턴을 사용처별로 결정한 ADR (결정 2) 에 맞춰 repository / application 을 정리한다. ADR-020 의 후속 트랙.

### 도메인 변경

- `Order`
  - `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "member_id", ..., foreignKey = @ForeignKey(name = "fk_order_member_id")) Member member` 필드 제거.
  - `@Column(name = "member_id", nullable = false) Long memberId` 필드 추가.
  - 정적 팩토리: `Order.create(Member)` → `Order.create(Long memberId)`.
  - `order.addOrderItem(Product, int)` → `order.addOrderItem(Long productId, int quantity)`. 내부에서 `OrderItem.of(this, productId, quantity)` 호출.
  - same-aggregate `@OneToMany List<OrderItem> orderItems` 는 그대로 유지 (cascade ALL, orphanRemoval 포함).
- `OrderItem`
  - `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id", ..., foreignKey = @ForeignKey(name = "fk_order_item_product_id")) Product product` 필드 제거.
  - `@Column(name = "product_id", nullable = false) Long productId` 필드 추가.
  - 정적 팩토리: `OrderItem.of(Order, Product, int)` → `OrderItem.of(Order, Long productId, int quantity)`.
  - same-aggregate `@ManyToOne Order order` (parent) 유지.
- 기존 `@Version`, `BaseTimeEntity`, 도메인 메서드, 검증 로직, exception 흐름은 그대로 유지한다.

### Repository 변경

- `JpaOrderRepository`
  - `findByIdAndMemberIdWithItems(Long id, Long memberId)`
    - 기존: `join fetch o.member`, `join fetch o.orderItems`, `join fetch oi.product` 사용.
    - 변경: cross-aggregate join fetch 제거. `join fetch o.orderItems` 만 유지. `where o.id = :id and o.memberId = :memberId` 로 컬럼 직접 비교.
  - `findByIdWithItems(Long id)`
    - 기존: `join fetch o.member`, `join fetch o.orderItems`, `join fetch oi.product` 사용.
    - 변경: 동일하게 `join fetch o.orderItems` 만 유지. `where o.id = :id`.
  - `findByMerchantPayKeyAndMemberId(...)`
    - 기존: `join o.member` (where 조건 join) 사용.
    - 변경: `join o.member` 제거. `where o.merchantPayKey = :merchantPayKey and o.memberId = :memberId`.
- derived query (예: `findBy...`) 가 association 객체 traversal 에 의존하면 (`...Member...`, `...Product...` 형태) 컬럼 기반으로 시그니처 정리.

### Application 변경

- Order 생성 경로 (`OrderConcurrencyService` 및 호출 흐름)
  - `Order.create(member)` 호출부를 `Order.create(memberId)` 로 변경.
  - `order.addOrderItem(product, qty)` 호출부를 `order.addOrderItem(productId, qty)` 로 변경.
  - product 존재 검증은 기존 `productRepository.findById(...)` / `findAllById(...)` 흐름을 그대로 유지한다. 호출처가 `product.getPrice()` 등 객체 필드를 같은 트랜잭션에서 함께 사용한다.
- `OrderCancelService`
  - `item.getProduct().getId()` → `item.getProductId()` 로 치환.
  - stock 복원 Map (`Map<Long, Integer>` productId → quantity) 구성을 OrderItem.productId 직접 사용으로 변경.
- `OrderExpirationService`
  - `orderItem.getProduct().getId()` → `orderItem.getProductId()` 치환.
  - stock 복원 Map 구성 동일하게 OrderItem.productId 직접 사용.
- `OrderQueryService`
  - `findByMerchantPayKeyAndMemberId` 호출 결과 사용 코드 변경 없음 (반환 타입 Order 동일). Order.getMemberId() 컬럼 사용.
- `PaymentReadyService`
  - 결제창 응답에 productName 노출이 필요하므로 아래 패턴으로 변경:
    ```
    Order order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
        .orElseThrow(...);
    List<Long> productIds = order.getOrderItems().stream()
        .map(OrderItem::getProductId)
        .toList();
    Map<Long, String> productNameByProductId = productRepository.findAllById(productIds).stream()
        .collect(Collectors.toMap(Product::getId, Product::getName));
    return PaymentReadyResult.from(order, productNameByProductId);
    ```
  - PaymentReady 응답 DTO 의 정적 팩토리 시그니처를 외부 주입 형태 (`from(Order, Map<Long, String>)`) 로 변경. 응답 필드 구성은 유지.

### Result DTO 변경

- 결제 준비 응답 DTO (productName 노출 대상)
  - 정적 팩토리 시그니처 `from(Order order)` → `from(Order order, Map<Long, String> productNameByProductId)` 형태로 변경.
  - 내부 productName 매핑은 외부 주입된 Map 사용.
  - 응답 필드 구성 (`orderId`, `merchantPayKey`, items 의 productId / productName / quantity / price 등) 유지.
- 그 외 Order 관련 응답 DTO (취소, 만료, 조회 응답)
  - productId / memberId 매핑이 OrderItem 컬럼 / Order 컬럼 직접 사용으로 바뀜.
  - 시그니처 변경 불필요 (외부 주입 컨텍스트 없음).

### Test fixture 변경

- 모든 `Order.create(member)` 호출부를 `Order.create(member.getId())` 로 갱신한다.
- 모든 `order.addOrderItem(product, qty)` 호출부를 `order.addOrderItem(product.getId(), qty)` 로 갱신한다.
- 모든 `OrderItem.of(order, product, qty)` 호출부를 `OrderItem.of(order, product.getId(), qty)` 로 갱신한다.
- 영향 파일 (grep 결과 기준 주요 위치):
  - `/src/test/java/com/commerce/order/domain/OrderTest.java`
  - `/src/test/java/com/commerce/order/application/OrderApplicationServiceTest.java`
  - `/src/test/java/com/commerce/order/application/OrderApplicationServiceIntegrationTest.java`
  - `/src/test/java/com/commerce/order/application/OrderExpirationServiceTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateProcessorTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateCartIntegrationTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateServiceConcurrencyTest.java`
  - `/src/test/java/com/commerce/order/application/concurrency/OrderConcurrencyServiceTest.java`
  - `/src/test/java/com/commerce/order/application/concurrency/OrderConcurrencyServiceDebugTest.java`
  - `/src/test/java/com/commerce/order/application/concurrency/OrderConcurrencyServiceDeadlockTest.java`
  - `/src/test/java/com/commerce/order/infrastructure/persistence/concurrency/OrderConcurrencyServiceDeadlockMysqlTest.java`
  - `/src/test/java/com/commerce/order/infrastructure/persistence/OrderRepositoryJpaAdapterTest.java`
  - `/src/test/java/com/commerce/payment/application/PaymentReadyServiceTest.java`
  - `/src/test/java/com/commerce/payment/application/NaverPayServiceIntegrationTest.java`
  - `/src/test/java/com/commerce/payment/application/NaverPayApprovalServiceTest.java`
  - `/src/test/java/com/commerce/payment/application/PaymentApprovalServiceTest.java`
  - `/src/test/java/com/commerce/payment/application/concurrency/PaymentCancellationAttemptServiceConcurrencyTest.java`
  - `/src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalAttemptServiceConcurrencyTest.java`
  - `/src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalServiceConcurrencyTest.java`
  - `/src/test/java/com/commerce/payment/application/concurrency/NaverPayServiceConcurrencyTest.java`
  - `/src/test/java/com/commerce/cart/application/GetMyCartServiceTest.java`
  - `/src/test/java/com/commerce/cart/application/concurrency/CartConcurrencyTest.java`
- 위 목록은 참고용이며, 실제 변경 시 추가 호출부가 컴파일 오류로 드러나면 모두 갱신한다.

### DB schema / Flyway

- 변경 없음. Flyway migration 파일 추가하지 않는다.
- DB FK 제약 (`fk_order_member_id`, `fk_order_item_product_id`) 그대로 유지.

## 수정 가능 경로

- `src/main/java/com/commerce/order/**`
- `src/main/java/com/commerce/payment/application/PaymentReadyService.java` 및 PaymentReady 응답 DTO
- `src/main/java/com/commerce/product/domain/repository/ProductRepository.java`
- `src/main/java/com/commerce/product/infrastructure/ProductRepositoryAdapter.java`
- `src/test/java/com/commerce/order/**`
- `src/test/java/com/commerce/payment/**` (order/product fixture 호출부 한정)
- `src/test/java/com/commerce/cart/**` (order/product fixture 호출부 한정)
- `docs/tasks/order-jpa-association-decouple/**`

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `Order` / `OrderItem` 에서 cross-aggregate `@ManyToOne` import (Member, Product 객체 의존) 가 제거됐는가?
   - `Order.orderItems` (`@OneToMany`), `OrderItem.order` (`@ManyToOne` parent) 같은 same-aggregate 관계는 그대로 유지됐는가?
   - 객체 traversal 이 entity / application 코드에서 남아있지 않은가? — 아래 명령 결과 0건 (test fixture 의 의도적 호출 제외):
     - `rg "order\.getMember\(\)" src/main`
     - `rg "\.getProduct\(\)\." src/main/java/com/commerce/order`
     - `rg "orderItem\.getProduct\(\)" src/main`
     - `rg "item\.getProduct\(\)" src/main/java/com/commerce/order`
   - `JpaOrderRepository` 의 JPQL 이 cross-aggregate `join fetch` 를 포함하지 않는가? — `rg "join fetch o\.member" src/main` / `rg "join fetch oi\.product" src/main` / `rg "join o\.member" src/main` 결과 0건.
   - `PaymentReadyService` 가 productIds 를 모아 `findAllById` 로 batch 조회하고 응답 DTO 에 productName Map 을 외부 주입하는가?
   - DB schema 변경 / Flyway V 파일 추가가 없는가? — `git diff src/main/resources/db/migration/` 결과 없음.
   - architecture.md 의 디렉토리 구조와 컨벤션을 따랐는가?
   - ADR-020 / ADR-011 등 상위 작업 규칙을 위반하지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `Order.orderItems`, `OrderItem.order` 같은 same-aggregate 관계를 해제하지 마라. 이유: ADR (결정 1) 가 same-aggregate 객체 참조 유지를 명시했고, ADR-020 의 "같은 aggregate 내 root-child 는 객체 참조 허용" 원칙과 일치한다.
- Flyway V 파일을 추가하지 마라. 이유: 본 sub-PR series 의 메타 원칙은 schema 변경 0건이고, FK 제거는 별도 트랙이다.
- DB FK 제약 (`fk_order_member_id`, `fk_order_item_product_id`) 을 제거하지 마라. 이유: 별도 트랙. JPA 매핑 차원에서만 association 해제한다.
- 응답 DTO 의 필드 구성을 변경하지 마라. 이유: 본 sub-PR 의 정책 목적은 association 해제 + fetch join 대체이지 응답 계약 정비가 아니다 (ADR 결정 5).
- fetch join 대체 패턴을 단일 원칙 (전부 DTO projection 또는 전부 batch composition) 으로 통일하지 마라. 이유: ADR (결정 2) 가 사용처별 분석 결과에 따라 패턴을 결합한다고 명시했다.
- `OrderItem` 에 productName 같은 도메인 본질 아닌 필드를 추가하지 마라. 이유: ADR-020 의 통증 #1 (편한 탐색 오용) 을 schema 로 박는 안티 패턴.
- 기존 테스트를 깨뜨리지 마라.
