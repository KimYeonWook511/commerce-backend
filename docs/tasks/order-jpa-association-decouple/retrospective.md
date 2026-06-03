# Order JPA Association Decouple Retrospective

## 개요

본 sub-PR은 ADR-020 후속 트랙의 두 번째 작업이다. `Stock.product(@OneToOne)` / `StockHistory.stock(@ManyToOne)` 을 해제한 선행 sub-PR (`stock-jpa-association-decouple`) 의 패턴과 메타 원칙을 그대로 계승하면서, Order 도메인 특유의 두 가지 결정을 본 sub-PR에서 처음으로 정립했다.

첫째, **fetch join 대체 패턴의 일반 원칙**이다. Stock 도메인에는 fetch join이 없어 선행 sub-PR이 의도적으로 미루어 둔 결정을 Order의 네 가지 사용처를 분석하며 사용처별 분석으로 정립했다.

둘째, **도메인 팩토리 시그니처의 Long ID 전환**이다. `Order.create(Member)` / `addOrderItem(Product, int)` 에서 객체 의존을 제거하고 `Order.create(Long memberId)` / `addOrderItem(Long productId, int)` 로 전환하면서, 존재 검증을 `productRepository.existsById()` 로 효율화했다.

DB schema 변경 없이 JPA 매핑 차원에서만 association을 해제한다는 series 메타 원칙은 그대로 유지됐다.

---

## 결정 흐름

### 1. fetch join 대체 패턴 — 단일 원칙 vs 사용처별 분석

선행 stock sub-PR의 ADR은 "fetch join 대체 정책을 Order sub-PR에서 사용처별 분석으로 처음 정립한다"라고 명시하며 이 결정을 의도적으로 위임했다. Order의 실제 사용처를 분석하니 네 가지 경로가 있었고, 필요한 데이터 양상이 경로마다 달랐다.

- `findByIdAndMemberIdWithItems` ← `PaymentReadyService`: 결제창에 productName 노출 필요.
- `findByIdAndMemberIdWithItems` ← `OrderCancelService`: productId로 stock 복원 Map 구성. productName 불필요.
- `findByIdWithItems` ← `OrderExpirationService`: 동일하게 productId만 필요.
- `findByMerchantPayKeyAndMemberId` ← `OrderQueryService`: where 조건 join이었고 반환 데이터에 cross-aggregate 없음.

단일 원칙 세 가지를 검토했다. P1(JPQL DTO projection 전체 통일)은 cancel/expiration 경로까지 productName을 끌어와 매핑에 불필요한 부담을 준다. P2(batch composition 전체 통일)도 cancel/expiration에 추가 쿼리를 강제한다. P3(QueryService 분리 전체 통일)는 단순한 cancel/expiration 경로까지 read 모델을 새로 만들어야 해 과한 추상화다.

결국 P4(사용처별 분석)를 채택했다. 기본 원칙 두 가지는 공통이다 — "same-aggregate fetch (`join fetch o.orderItems`) 는 유지, cross-aggregate fetch 는 모두 제거". 이 위에서 데이터 양상에 맞게 패턴을 다르게 적용한다. cancel/expiration은 OrderItem 컬럼에 productId가 이미 있어 추가 쿼리 0회. PaymentReady만 `productRepository.findAllById(productIds)` 1회를 추가하고 응답 DTO에 productName Map을 외부 주입한다.

이로써 fetch join 대체의 일반 원칙이 Order sub-PR에서 처음으로 명문화됐다. 후속 Payment sub-PR은 이 일반 원칙을 따르되 Payment 도메인의 사용처별 양상에 맞춰 패턴을 결정하면 된다.

### 2. `Order.create` / `addOrderItem` 시그니처 — Long ID 전환과 existsById 효율화

`@ManyToOne Member` / `@ManyToOne Product` association을 제거하면 도메인 팩토리에 Member/Product 객체를 넘기는 명목적 이유가 사라진다. 세 가지 옵션을 검토했다.

A안(시그니처 유지)은 application이 Product를 계속 `findById`로 로드해 도메인에 넘기는 구조다. JPA association 해제 후 도메인이 Product 객체를 받아서 할 수 있는 일이 없어지므로 의미 없는 로드가 된다. 단위 테스트 fixture도 Member/Product entity를 계속 만들어야 한다.

B안(Long ID 시그니처, existsById 없이)은 application이 `findById`로 존재 검증 후 ID만 전달한다. A안보다 낫지만 `findById`는 모든 컬럼을 SELECT한다. 결제·주문 hot path에서 단순 존재 검증을 위해 Product 전체 row를 로드할 필요가 없다.

C안(Long ID + existsById)을 채택했다. `ProductRepository.existsById(Long productId)` 메서드를 신설해 `SELECT 1 FROM tbl_product WHERE id = ?` 1행 boolean 조회로 효율화한다. 단, 같은 트랜잭션에서 Product의 다른 필드(가격, 상태 등)가 필요한 사용처는 기존 `findById` 를 그대로 유지한다. 용도와 필요 데이터에 맞게 검증 방식을 분기한다는 원칙이다.

부수 효과로 unit test fixture 부담이 줄었다. `Order.create(memberId)`, `addOrderItem(productId, qty)` 호출에 Member/Product entity를 만들지 않아도 된다.

### 3. same-aggregate 관계 유지 — `Order.orderItems` / `OrderItem.order`

ADR-020은 "같은 aggregate 내 root-child는 객체 참조 허용"이라고 명시한다. Order와 OrderItem이 같은 aggregate에 속하는지가 판단의 핵심이었다.

두 가지 근거로 같은 aggregate로 판단했다. 첫째, `cascade ALL, orphanRemoval = true` 의 lifecycle 결합이 강하다. Order가 삭제되면 OrderItem도 함께 삭제되며, OrderItem은 Order 없이 독립적으로 존재하지 않는다. 둘째, cart나 다른 신규 도메인의 "단일 entity aggregate" 또는 "독립 aggregate" 패턴과 비교했을 때, Order ↔ OrderItem은 lifecycle 결합이 훨씬 강해 분리 시 일관성 모델이 어긋난다.

반대로 Member와 Product는 Order의 lifecycle과 독립적이다. Member가 탈퇴해도 Order 이력은 남아야 하고, Product가 판매 중단되어도 기존 주문의 OrderItem 이력은 유지된다. ID 참조로 충분하다는 판단이 자연스러웠다.

### 4. 응답 echo 정리 별도 트랙 — 선행 stock sub-PR 정책 계승

선행 stock sub-PR의 ADR 결정 2가 "응답 echo 정리는 본 sub-PR 범위가 아니다"를 먼저 정립했다. 동일 정책을 Order sub-PR에도 그대로 적용했다.

본 sub-PR의 정책 목적은 cross-aggregate ID 참조 통일과 fetch join 대체 패턴 정립이다. PaymentReady 응답의 orderId/merchantPayKey echo 구조나 OrderItem의 productId echo 등 응답 필드 구성 정비는 다른 축의 결정이다. 두 가지를 한 PR에 묶으면 "association 해제"와 "응답 계약 정비"가 같은 diff에서 읽혀 PR 메시지가 흐려진다.

---

## 기각된 옵션

| 옵션 | 검토 이유 | 기각 사유 |
|---|---|---|
| fetch join 대체 단일 원칙(P1: DTO projection) | 전체 통일로 단순화 | cancel/expiration에도 productName 조립 강제. 불필요한 부담 |
| fetch join 대체 단일 원칙(P2: batch composition) | 전체 통일로 단순화 | cancel/expiration에 추가 쿼리 강제. productId만 있으면 충분한 경로에 과도 |
| fetch join 대체 단일 원칙(P3: QueryService 분리) | read 모델 일관성 | 단순한 cancel/expiration 경로까지 read 모델 신설. 과한 추상화 |
| `Order.create` 시그니처 유지(A안) | 변경 최소화 | association 해제 후 도메인에 객체 넘기는 명목 사라짐. fixture 부담 존속 |
| Long ID 시그니처 + `findById` 유지(B안) | 검증 흐름 유지 | `findById`는 모든 컬럼 SELECT. hot path에서 불필요한 row 로드 |
| `Order.orderItems` / `OrderItem.order` 관계 해제 | 일관된 cross-aggregate 원칙 적용 | cascade ALL + orphanRemoval의 lifecycle 결합이 강함. ADR-020의 "같은 aggregate 내 root-child 허용" 위반 |
| 응답 echo 정리 포함 | 한 PR에서 완결 | 본 sub-PR의 정책 목적과 다른 축. PR 메시지 흐림 |

---

## 후속 트랙으로 넘기는 baseline

### Payment sub-PR이 본 sub-PR을 참조하는 방법

**fetch join 대체 일반 원칙** (본 sub-PR에서 처음 정립):

- same-aggregate fetch join(`join fetch o.orderItems` 류)은 유지한다.
- cross-aggregate fetch join은 모두 제거한다.
- 필요한 cross-aggregate 데이터는 사용처별 양상에 맞게 결정한다:
  - 컬럼에 이미 있는 ID → 추가 조회 0회.
  - 외부 필드(name 등)가 필요한 경우 → `findAllById` batch 1회 + 응답 DTO 외부 주입.
- Payment 도메인도 사용처별 분석을 거쳐 패턴을 적용한다.

**도메인 시그니처 패턴** (본 sub-PR에서 Long ID 전환 완성):

- 팩토리 메서드는 Long ID 시그니처. 호출자가 존재 검증 후 ID만 전달.
- 존재 검증은 객체 필드가 필요 없는 사용처에서 `existsById` 로 효율화. 같은 트랜잭션에서 객체 필드가 필요한 사용처는 `findById` 유지.

**메타 원칙 (series 전체 공통)**:

- schema 변경 0건. Flyway V 파일 추가 없음. FK 제약 유지.
- 응답 계약 무변경. 내부 조립 방식만 교체.
- Hibernate `validate` 통과 기준: ID 필드 `@Column(name = "xxx_id", nullable = false)` 유지만으로 통과. 선행 stock / 본 Order sub-PR 모두 integrationTest(Testcontainers MySQL)에서 확인.

---

## 운영 점검

**DB FK가 schema에 남아있는 상태**: `fk_order_member_id` / `fk_order_item_product_id` FK 제약이 schema에 그대로 남아있고 JPA가 더 이상 인식하지 않는다. DB 차원의 referential integrity는 유지된다. Member 삭제나 Product 삭제 시도 시 FK violation이 발생하면 기존과 동일하게 GlobalExceptionHandler 500 안전망으로 위임된다.

이 상태(코드에서 association 해제, schema에 FK 존재)는 Payment sub-PR 머지 후 Issue #195를 close하고 FK 일괄 제거 Flyway migration을 별도 issue/PR로 발행할 때까지 유지된다.

**PaymentReady의 batch 쿼리 1회 추가**: `productRepository.findAllById(productIds)`가 PaymentReady 흐름에 추가됐다. 단일 주문의 OrderItem 개수는 보통 한 자릿수이고, IN 절 1회는 hot path 영향이 미미하다. 기존에는 `join fetch oi.product`로 같은 쿼리 한 번에 OrderItem과 Product를 모두 로드했는데, 이제 쿼리가 두 번(Order+OrderItems 조회 1회, Product batch 조회 1회)으로 분리된다. 총 DB round-trip이 1회 늘어나는 것이나 트랜잭션 범위는 동일하고, 선행 쿼리의 cartesian product(join fetch oi.product 시 OrderItem × Product row 조합)를 피하는 효과도 있다.

---

## 자기 평가

### 잘된 점

- **fetch join 대체 일반 원칙 최초 정립**: 선행 stock sub-PR이 의도적으로 위임한 결정을 사용처별 분석으로 명문화했다. cancel/expiration 경로에서 cross-aggregate 추가 쿼리 0회를 확보했고, 일반 원칙이 이후 Payment sub-PR의 가이드로 남는다.
- **existsById 신설로 검증 효율화**: 결제·주문 hot path의 product 존재 검증이 `SELECT 1` 1행 조회로 개선됐다. 단순하지만 목적에 맞는 API 분리다.
- **Hibernate validate 검증**: Testcontainers로 실제 MySQL을 기동하는 `integrationTest`를 포함해 validate 통과가 모델 진술이 아닌 실행 결과로 확인됐다. 선행 stock sub-PR의 동일 검증을 Order 도메인에서도 반복해 series 전체의 신뢰도를 높였다.
- **PaymentReadyResult 외부 주입 패턴**: stock sub-PR의 `from(history, productId)` 패턴이 `from(order, productNameByProductId)` 로 자연스럽게 확장됐다. application이 응답을 의도적으로 조립한다는 의도가 시그니처에 드러난다.

### 아쉬운 점

- **fixture 변경 면적이 payment / cart까지 침투**: `Order.create(member)`, `addOrderItem(product, qty)` 호출부가 order 도메인 외부(payment, cart)의 테스트까지 퍼져 있어 변경 면적이 컸다. 선행 stock sub-PR에서도 동일한 현상이 있었는데, cross-aggregate 객체 참조가 test fixture에서도 도메인 경계를 넘어 침투한 결과다. 향후 Payment sub-PR에서도 동일한 면적 확산이 예상된다.
- **응답 echo 정리를 별도 트랙으로 미룸**: PaymentReady 응답의 merchantPayKey echo 구조 등이 이번 리팩토링으로 코드에서 더 명시적으로 드러났다. 본 sub-PR 범위에서 분리한 것은 정책적으로 옳으나, 적절한 시점에 별도 트랙 정리가 필요하다.
