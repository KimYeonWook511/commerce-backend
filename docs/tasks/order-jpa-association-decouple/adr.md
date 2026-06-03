# 태스크 ADR

## 결정 1: Order·OrderItem 의 JPA cross-aggregate association 을 해제하고 Long ID 로 전환한다 (ADR-020 후속 트랙)

### 배경

- ADR-020 은 신규 도메인 (cart) 부터 cross-aggregate 를 `Long` ID 로 참조한다고 결정했으나, 기존 도메인은 호환성 부담으로 별도 트랙으로 분리됐다.
- Issue #195 가 별도 트랙을 도메인 경계 단위 sub-PR 로 진행 중. 본 태스크는 선행 `stock-jpa-association-decouple` (#199) 에 이은 **두 번째 sub-PR**.
- 진행 단위는 선행 sub-PR 의 결정 1 (도메인 경계 단위 sub-PR 분리) 을 그대로 따른다.

### 결정 내용

- Order / OrderItem aggregate 의 cross-aggregate association 만 해제한다.
  - `Order.member` (`@ManyToOne Member`) → `Long memberId`.
  - `OrderItem.product` (`@ManyToOne Product`) → `Long productId`.
- same-aggregate 관계는 유지한다.
  - `Order.orderItems` (`@OneToMany OrderItem`) 유지.
  - `OrderItem.order` (`@ManyToOne Order` parent) 유지.
- Order 와 OrderItem 은 같은 aggregate 의 root-child 로 다룬다. cascade/orphanRemoval 의 lifecycle 결합을 유지한다.

### 근거

- ADR-020 의 cross-aggregate ID 참조 원칙을 Order 도메인에 동일하게 적용한다.
- Order ↔ OrderItem 은 lifecycle 결합 (cascade ALL, orphanRemoval) 이 강한 root-child 관계로, ADR-020 의 "같은 aggregate 내 root-child 는 객체 참조 허용" 에 해당한다. 분리 시 cart 같은 신규 도메인의 lifecycle 일관성 모델과 어긋난다.
- Member, Product 는 Order 의 lifecycle 과 독립적이며 ID 참조로 충분하다.

### 결과

- Order / OrderItem aggregate 가 외부 aggregate (Member, Product) 를 ID 로만 참조.
- Order ↔ OrderItem 은 객체 참조 유지로 기존 cascade/orphanRemoval 동작 보존.
- Issue #195 의 다음 sub-PR (`payment-jpa-association-decouple`) 으로 이어진다.
- 모든 sub-PR 머지 후 DB FK 일괄 제거는 별도 트랙.

## 결정 2: fetch join 대체는 사용처별 분석으로 정책을 다르게 적용한다

### 배경

- 선행 stock sub-PR 의 결정 4 가 fetch join 대체 정책을 본 sub-PR (Order) 의 핵심 결정으로 위임했다.
- 현재 `JpaOrderRepository` 의 fetch join 사용처는 세 메서드 / 네 호출 경로다.

| 메서드 | join fetch 절 | 호출 service | 필요한 cross-aggregate 데이터 |
|---|---|---|---|
| `findByIdAndMemberIdWithItems` (1) | `o.member`, `o.orderItems`, `oi.product` | `PaymentReadyService` | productName (결제창 노출) |
| `findByIdAndMemberIdWithItems` (2) | (위와 동일 메서드) | `OrderCancelService` | productId (stock 복원) |
| `findByIdWithItems` | `o.member`, `o.orderItems`, `oi.product` | `OrderExpirationService` | productId (stock 복원) |
| `findByMerchantPayKeyAndMemberId` | `o.member` (no fetch, where 조건 join) | `OrderQueryService` | 없음 |

- 옵션:
  - (P1) JPQL 명시 join + DTO projection 단일 원칙
  - (P2) batch composition (cart 패턴) 단일 원칙
  - (P3) read 전용 QueryService 분리 단일 원칙
  - (P4) **사용처별 분석 후 데이터 필요 양상에 맞춰 패턴 결합**

### 결정 내용

- (P4) 사용처별 분석을 적용한다. 단일 원칙을 강제하지 않는다.
- 공통 기본 원칙 2가지:
  1. same-aggregate fetch join (`join fetch o.orderItems`) 은 유지한다.
  2. cross-aggregate fetch join (`join fetch o.member`, `join fetch oi.product`) 은 모두 제거한다.
- 사용처별 결정:
  - `findByIdAndMemberIdWithItems` JPQL: same-aggregate fetch 만 유지, `where o.id = :id and o.memberId = :memberId` 컬럼 비교.
    - `PaymentReadyService` 호출 경로: OrderItems 의 productId 목록을 모아 `ProductRepository.findAllById(productIds)` 1회로 batch 조회. productName Map 을 응답 DTO 에 외부 주입.
    - `OrderCancelService` 호출 경로: OrderItem.productId 컬럼 직접 사용. cross-aggregate 추가 조회 0회.
  - `findByIdWithItems` JPQL: 동일 처리. `OrderExpirationService` 는 OrderItem.productId 직접 사용.
  - `findByMerchantPayKeyAndMemberId` JPQL: `join o.member` 제거, `where o.memberId = :memberId` 컬럼 비교로 단순화.

### 근거

- **데이터 필요 양상이 사용처별로 다르다**. cancel/expiration 은 productId 만 필요하므로 추가 쿼리 0개가 가능하다 (OrderItem 컬럼에 이미 있음). PaymentReady 만 productName 노출이 필요하므로 batch 1회를 추가한다.
- **단일 원칙은 비효율을 강제한다**. 전부 DTO projection 으로 통일하면 cancel/expiration 에도 productName 을 끌어와 응답 매핑에 불필요한 부담을 준다. 전부 QueryService 분리는 단순한 cancel/expiration 경로까지 read 모델을 새로 만들어야 해 과한 추상화.
- **stock sub-PR 의 외부 주입 패턴이 자연스럽게 확장된다**. PaymentReady 응답 조립은 `from(history, productId)` 와 같은 정신으로 `from(order, productNameByProductId)` 시그니처가 된다. application 이 응답을 의도적으로 조립한다는 의도가 코드 표면에 드러난다.
- **N+1 회피는 batch 조회 1회로 충분**. 단일 order 의 OrderItem 개수가 보통 한 자릿수이고, IN 절 1회는 hot path 영향이 미미하다.

### 결과

- fetch join 대체 정책의 일반 원칙 (same-aggregate 유지, cross-aggregate 제거) 이 본 sub-PR 에서 처음 정립된다.
- 사용처별 분석 결과는 PaymentReady 경로의 batch composition + 외부 주입, 나머지 경로의 컬럼 직접 사용으로 정리된다.
- 후속 Payment sub-PR 도 동일 일반 원칙을 따르되, Payment 도메인의 사용처별 양상에 맞춰 패턴을 결정한다.

## 결정 3: Order.create / addOrderItem 시그니처를 Long ID 로 전환한다

### 배경

- 현재 `Order.create(Member)`, `order.addOrderItem(Product, int)` 는 객체 의존을 강제한다.
- application 은 product 존재 검증을 위해 `productRepository.findById(productId)` 로 Product entity 를 로드한 뒤 그대로 도메인에 넘긴다.
- JPA association 해제 후 도메인에 객체를 넘기는 명목적 이유가 사라진다. 시그니처를 재정비할 자연스러운 시점.
- 옵션:
  - (A) 시그니처 유지 — application 에서 Product 를 계속 로드해 넘긴다.
  - (B) Long ID 시그니처 — `Order.create(Long memberId)`, `addOrderItem(Long productId, int)`. application 은 기존 `findById` / `findAllById` 로 검증 + 가격 등 필드 조회 후 ID 만 도메인에 전달.

### 결정 내용

- (B) 를 채택한다.
- `Order.create(Long memberId)`, `order.addOrderItem(Long productId, int quantity, int unitPrice)`, `OrderItem.of(Order, Long productId, int quantity)` 로 시그니처 전환.
- application 의 product 존재 검증은 기존 `productRepository.findById(productId)` / `findAllById(productIds)` 흐름을 그대로 유지한다. 호출처가 동일 트랜잭션에서 `product.getPrice()` 등 객체 필드를 함께 사용하므로 객체 로드가 어차피 필요하다.

### 근거

- 도메인 invariant 가 ID 기준으로 명확해진다. Order/OrderItem entity 가 외부 객체에 의존하지 않는다.
- test fixture 의 builder 시그니처 부담이 줄어든다. unit test 에서 Member/Product entity 를 만들지 않아도 ID 만으로 Order 를 만들 수 있다.

### 결과

- domain layer 의 외부 객체 의존 0건.
- application 의 product 조회 패턴은 기존 그대로 유지 (`findById`, `findAllById`).
- `ProductRepository` 인터페이스에 신규 메서드 추가 없음.

## 결정 4: DB schema 변경 / Flyway migration 없이 진행한다 (메타 원칙 재확인)

### 배경

- 선행 stock sub-PR 의 결정 3 으로 이미 정립된 본 series 의 메타 원칙: "코드 차원 association 해제만, schema 변경 0건".
- Order 도메인의 컬럼 (`member_id`, `product_id`) 은 이미 존재한다. JPA 매핑만 association 해제하면 schema 변경 없이 완결 가능.
- DB FK 제약 (`fk_order_member_id`, `fk_order_item_product_id`) 은 schema 에 남아있고 JPA 가 더 이상 인식하지 않을 뿐.

### 결정 내용

- FK 제약 유지, Flyway migration 없이 진행한다.
- DB FK 일괄 제거는 Issue #195 의 모든 sub-PR 완료 후 별도 트랙에서 진행한다.

### 근거

- 본 series 의 메타 원칙 (`docs/tasks/stock-jpa-association-decouple/adr.md` 결정 3) 을 그대로 따른다.
- Issue #195 본문 "DB FK 제약조건 일괄 제거 — 모든 코드 마이그레이션 완료 후 별도 PR/Issue 에서 진행" 명시.
- Hibernate `validate` 는 컬럼 단위 검증이고 FK 제약 존재 여부는 검증 대상이 아니다. `@ManyToOne` 제거 후에도 `@Column(name = "member_id", nullable = false)`, `@Column(name = "product_id", nullable = false)` 매핑은 유지되므로 validate 통과 가능 (선행 stock sub-PR 에서 동일 패턴 검증됨).

### 결과

- 본 sub-PR 의 변경은 코드 / JPA 매핑 / 응답 조립 패턴에 한정.
- DB FK 제거 트랙은 모든 sub-PR (Payment 포함) 완료 후 Issue #195 의 후속 issue 로 발행.

## 결정 5: 응답 echo 정리는 본 sub-PR 의 범위가 아니다

### 배경

- 선행 stock sub-PR 이 응답 echo (path productId 를 응답 필드에 그대로 echo 하는 구조) 정리를 별도 트랙으로 분리했다.
- Order/Payment 응답에도 유사한 echo 구조 (예: PaymentReady 응답의 orderId / merchantPayKey 등) 가 존재할 수 있다.

### 결정 내용

- 본 sub-PR 에서는 응답 필드 구성을 변경하지 않는다.
- productName 외부 주입 등 새로 도입되는 매핑 패턴은 응답 계약 변경 없이 내부 조립 방식만 바꾼다.
- 응답 echo 정리는 별도 트랙.

### 근거

- 본 sub-PR 의 정책 목적은 cross-aggregate ID 참조 통일과 fetch join 대체 패턴 정립이지 응답 계약 정비가 아니다.
- 두 가지를 한 PR 에 묶으면 PR 메시지가 흐려진다 (`docs/commit-conventions.md` "역할이 다른 변경을 이유 없이 하나로 묶지 않는다").
- 선행 stock sub-PR 의 결정 2 와 일관.

### 결과

- API 응답 계약 유지. frontend 영향 없음.
- 응답 echo 정리는 후속 별도 트랙으로 분리.
