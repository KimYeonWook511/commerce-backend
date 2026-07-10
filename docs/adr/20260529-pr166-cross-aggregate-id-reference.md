# 신규 도메인의 cross-aggregate 참조는 ID로 한다

- Status: accepted
- Date: 2026-05-29

## Context

기존 도메인은 `Order.member`, `OrderItem.product`, `Stock.product` 등 `@ManyToOne` 객체 참조를 광범위하게 사용한다. 그러나 application 계층은 대부분 `memberId`, `productId` 등 ID 기반으로 흐름을 다루고 있어 도메인 모델과 application 인터페이스 사이에 이중 표현이 발생한다. 이로 인해 N+1 회피와 fetch join 부담, 도메인 결합도 증가, 단위 테스트에서의 객체 그래프 구성 부담, DDD "다른 aggregate는 ID로만 참조" 원칙 위반 등 누적 부채가 있었다. 신설 도메인부터라도 기본값을 ID 참조로 두자는 결정이다.

DDD 정통(Eric Evans, "Reference Other Aggregates Only By Identity") 원칙에 부합한다. (a) 다른 aggregate와의 결합도가 감소해 도메인 변경 영향 반경이 좁아진다. (b) JPA lifecycle 함정(detached entity, cascade, lazy proxy)을 피할 수 있다. (c) 단위 테스트가 원시 ID로 단순화되어 객체 그래프 setup 부담이 사라진다. (d) 향후 마이크로서비스 분리 시 aggregate 경계가 서비스 경계와 자연스럽게 정렬된다. cart 조회 시 `productRepository.findAllById(productIds)`로 명시적으로 Product를 한 번 더 조회해 응답을 조립하는 비용은 PK 기반 인덱스 조회라 무시 가능하다.

## Decision

본 phase의 `cart` 도메인을 기점으로, 이후 신설되는 모든 도메인은 다른 aggregate를 `Long` ID로만 참조한다. `@ManyToOne`, `@JoinColumn`, cross-aggregate `@OneToOne` 사용을 금지한다. `cart`의 `CartItem`은 `memberId`, `productId`를 원시 `Long`으로 저장하며 다른 aggregate를 객체로 참조하지 않는다.

- **적용 범위**: 본 ADR 이후 신설되는 모든 cross-aggregate 참조에 적용한다. 같은 aggregate 내 root-child 관계(예: `Order ↔ OrderItem` 같이 동일 aggregate 안의 collection)는 본 정책 대상이 아니며 기존대로 객체 참조를 허용한다. 기존 cross-aggregate 객체 참조의 ID 참조로의 마이그레이션은 별도 작업으로 다룬다.

상세는 `docs/tasks/cart/adr.md` 결정 2 참조.

## Consequences

DB 참조 무결성을 FK 제약이 보장하지 않는다. 대신 application 흐름·UNIQUE 제약·삭제 순서 정책이 정합성을 책임진다. 기존 Order/Stock/StockHistory 등의 `@ManyToOne` 참조는 호환성 부담이 크고 본 phase 범위가 아니므로 마이그레이션하지 않고 별도 트랙으로 분리한다.

- **후속 (stock-jpa-association-decouple, 2026-06-03)**: Stock·StockHistory aggregate 에 본 ADR 의 cross-aggregate ID 참조 원칙이 적용됐다. JPA `@OneToOne`(`Stock.product`) / `@ManyToOne`(`StockHistory.stock`) 을 제거하고 `Long productId` / `Long stockId` 필드로 전환했다. DB schema (컬럼·FK) 변경 없음. 세부 결정 (응답 조립 외부 주입 패턴, schema 무변경 원칙) 은 `docs/tasks/stock-jpa-association-decouple/adr.md` 참조. 후속 트랙: `order-jpa-association-decouple`, `payment-jpa-association-decouple`.
- **후속 (order-jpa-association-decouple, 2026-06-03)**: Order / OrderItem aggregate 에 본 ADR 의 cross-aggregate ID 참조 원칙이 적용됐다. JPA `@ManyToOne`(`Order.member` → `memberId: Long`, `OrderItem.product` → `productId: Long`) 을 제거하고 `Long` ID 필드로 전환했다. same-aggregate 관계(`Order.orderItems` / `OrderItem.order`)는 객체 참조 유지. DB schema (컬럼·FK) 변경 없음. fetch join 대체 원칙 (same-aggregate 유지 / cross-aggregate 제거) 과 사용처별 패턴 (PaymentReady 는 batch composition + 외부 주입, cancel/expiration 은 컬럼 직접 사용) 을 본 sub-PR 에서 처음 명문화했다. 세부 결정은 `docs/tasks/order-jpa-association-decouple/adr.md` 참조. 후속 트랙: `payment-jpa-association-decouple`.
- **후속 (payment-jpa-association-decouple, 2026-06-03)**: Payment aggregate 에 본 ADR 의 cross-aggregate ID 참조 원칙이 적용됐다. JPA `@OneToOne`(`Payment.order`) 을 제거하고 `Long orderId` 필드로 전환했다. `Payment.createCompleted` 정적 팩토리 시그니처를 `(Long orderId, int amount, ...)` 로 전환해 도메인의 외부 객체 의존을 0으로 만들었다. DB schema (컬럼·FK) 변경 없음. 세부 결정은 `docs/tasks/payment-jpa-association-decouple/adr.md` 참조. **본 ADR 후속 트랙 (Stock / Order / Payment) 완료.** 후속 DB FK 일괄 제거 (fk_stock_product_id, fk_stock_history_stock_id, fk_order_member_id, fk_order_item_product_id, fk_payment_order_id) 는 별도 issue 발행 예정.
- **후속 (cross-aggregate-fk-cleanup, 2026-06-03)**: 본 ADR 후속 트랙 series 완전 종료. 단일 Flyway V4 migration (`V4__drop_cross_aggregate_fk_constraints.sql`) 으로 cross-aggregate FK 5건 (`fk_stock_product_id`, `fk_stock_history_stock_id`, `fk_order_member_id`, `fk_order_item_product_id`, `fk_payment_order_id`) 을 일괄 제거했다. UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 과 same-aggregate FK (`fk_order_item_order_id`) 는 유지한다. 코드 + DB schema 정합성이 회복됐다 (코드 차원 cross-aggregate association 0건 + DB cross-aggregate FK 0건). 운영 DB 의 FK 제거 적용 절차는 별도 결정. 세부 결정은 `docs/tasks/cross-aggregate-fk-cleanup/adr.md` 참조.
