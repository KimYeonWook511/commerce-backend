# 태스크 PRD

## 태스크명

- `order-jpa-association-decouple`

## 배경

- ADR-020 으로 신규 도메인 (cart) 부터 cross-aggregate 를 `Long` ID 로만 참조하기로 결정했으나, 기존 도메인 (Stock / Order / Payment 등) 은 호환성 부담을 이유로 마이그레이션을 보류했다.
- Issue #195 가 보류해둔 별도 트랙을 도메인 경계 단위 sub-PR 로 점진 진행한다. 본 태스크는 그 **두 번째 sub-PR** 로 **Order / OrderItem 도메인의 JPA Entity 간 cross-aggregate association 을 해제**한다.
- 선행 sub-PR: `stock-jpa-association-decouple` (#199, 머지 완료) — 동일 패턴 / 메타 원칙을 그대로 따른다.
- 후속 sub-PR: `payment-jpa-association-decouple`.

## 목표

- Order / OrderItem 도메인의 JPA `@ManyToOne` cross-aggregate 객체 참조를 `Long` ID 필드로 전환한다.
- ADR-020 의 cross-aggregate ID 참조 정신을 Order aggregate 에 적용한다.
- Order / OrderItem aggregate 가 다른 aggregate (Member, Product) 를 객체 그래프로 traverse 하지 않게 한다.
- 본 sub-PR 의 새 결정: **fetch join 대체 패턴**을 사용처별 분석으로 처음 정립한다 (선행 stock sub-PR 이 의도적으로 미루어 둔 결정).

## 범위

### 포함 범위

- `Order.member` (Member 객체, `@ManyToOne`) → `Long memberId`. JPA association 매핑 제거.
- `OrderItem.product` (Product 객체, `@ManyToOne`) → `Long productId`. JPA association 매핑 제거.
- `JpaOrderRepository` 의 fetch join JPQL 정리 — `join fetch o.member`, `join fetch oi.product` 제거. same-aggregate `join fetch o.orderItems` 는 유지.
- application 계층의 객체 traversal (`order.getMember().getId()`, `orderItem.getProduct().getId()`, `getProduct().getName()` 등) 정리.
- `OrderConcurrencyService` 의 `Order.create(Member)` / `addOrderItem(Product, int)` 호출부 Long ID 시그니처 전환.
- `ProductRepository.existsById(Long productId)` 메서드 신설 — application 의 product 존재 검증을 `findById` 객체 로드에서 `existsById` boolean 조회로 효율화.
- `PaymentReadyService` 의 productName 표시를 위한 batch composition 도입 — `ProductRepository.findAllById(productIds)` 로 productName Map 조회 후 응답 DTO 에 외부 주입.
- test fixture 의 `Order.builder().member(...)`, `OrderItem.builder().product(...)`, `Order.create(member)`, `addOrderItem(product, qty)` 호출부 정리. order, payment, cart 도메인 테스트까지 갱신.
- 루트 `docs/ADR.md`, `docs/architecture.md` 동기화.
- 회고록 작성.

### 제외 범위

- **DB schema 변경 / Flyway migration** — 컬럼 (`member_id`, `product_id`) 은 그대로. JPA 매핑만 해제.
- **DB FK 제약 제거** — `fk_order_member_id`, `fk_order_item_product_id` 유지. 모든 도메인 association 해제 완료 후 별도 트랙에서 일괄 정리.
- **응답 API 계약 정비** — `productId` 등 path echo 응답 필드 정비는 별도 트랙.
- **Payment 도메인의 cross-aggregate association 해제** — 후속 sub-PR.
- **같은 aggregate 내 root-child 관계 (`Order.orderItems`, `OrderItem.order`)** — 유지 대상. ADR-020 의 "같은 aggregate 내부는 객체 참조 허용".

## 주요 시나리오

- 사용자가 장바구니에서 주문을 생성한다.
- 사용자가 결제 준비를 요청해 결제창에 띄울 정보 (상품명 포함) 를 받는다.
- 사용자가 주문을 취소하면 stock 이 복원된다.
- 결제 대기 만료 배치가 미결제 주문을 만료 처리하고 stock 을 복원한다.
- 위 시나리오 모두 기존과 동일하게 동작하되, JPA 매핑 차원에서 `Order.member` / `OrderItem.product` 객체 참조를 사용하지 않는다.

## 요구사항

- `Order` 는 `memberId: Long` 을 가진다. `@ManyToOne Member` 제거.
- `OrderItem` 은 `productId: Long` 을 가진다. `@ManyToOne Product` 제거.
- `Order.orderItems` (`@OneToMany`), `OrderItem.order` (`@ManyToOne` parent) 은 same-aggregate 관계로 유지한다.
- `Order.create(Long memberId)` / `order.addOrderItem(Long productId, int quantity)` 시그니처. 호출자 (application) 가 product 존재 검증 후 productId 만 전달한다.
- `ProductRepository` 에 `boolean existsById(Long productId)` 메서드 신설.
- `JpaOrderRepository` 의 fetch join JPQL 은 same-aggregate `join fetch o.orderItems` 만 유지. cross-aggregate `join fetch o.member`, `join fetch oi.product` 제거. `join o.member` 같은 where 보조 join 도 제거하고 `where o.memberId = :memberId` 로 단순화.
- `PaymentReadyService` 는 OrderItem productId 목록으로 `ProductRepository.findAllById(productIds)` 를 batch 호출해 productName Map 을 만들고, 응답 DTO 조립 시 외부 주입한다.
- `OrderCancelService`, `OrderExpirationService` 는 OrderItem.productId 를 직접 사용해 stock 복원 Map 을 만든다 (별도 Product 조회 불필요).
- 기존 `./gradlew test integrationTest` 통과.

## 제약사항

- DB schema (테이블 / 컬럼 / FK) 는 손대지 않는다. Hibernate `validate` 통과 가능해야 한다.
- API 응답 계약 (PaymentReadyResult 등) 의 필드 구성 유지.
- 동시성 / 보상 흐름의 회귀 없음 — `OrderConcurrencyService`, `OrderCreateProcessor`, payment 도메인의 order 의존 흐름 동작 보존.
- ADR-020 의 적용 범위 ("같은 aggregate 내 root-child 는 객체 참조 허용") 를 본 태스크에 그대로 따른다.
- 본 sub-PR series 의 메타 원칙 (`docs/tasks/stock-jpa-association-decouple/adr.md` 결정 3) "코드 차원 association 해제만, schema 변경 0건" 을 유지한다.
