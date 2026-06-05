# 태스크 PRD

## 태스크명

- `stock-jpa-association-decouple`

## 배경

- ADR-020 으로 신규 도메인 (cart) 부터 cross-aggregate 를 `Long` ID 로만 참조하기로 결정했으나, 기존 도메인 (Stock / Order / Payment 등) 은 호환성 부담을 이유로 마이그레이션을 보류했다.
- Issue #195 가 보류해둔 별도 트랙을 진행하기로 결정했고, 본 태스크는 그 첫 sub-PR 로 **Stock / StockHistory 도메인의 JPA Entity 간 cross-aggregate association 을 해제**한다.
- 후속 sub-PR: `order-jpa-association-decouple`, `payment-jpa-association-decouple`.

## 목표

- Stock / StockHistory 도메인의 JPA `@OneToOne` / `@ManyToOne` cross-aggregate 객체 참조를 `Long` ID 필드로 전환한다.
- ADR-020 의 cross-aggregate ID 참조 정신을 기존 도메인에 적용한다.
- Stock / StockHistory aggregate 가 다른 aggregate 를 객체 그래프로 traverse 하지 않게 한다.

## 범위

### 포함 범위

- `Stock.product` (Product 객체) → `Long productId`. `@OneToOne` 매핑 제거.
- `StockHistory.stock` (Stock 객체) → `Long stockId`. `@ManyToOne` 매핑 제거.
- JPQL / derived query 시그니처 정리.
- application 계층의 객체 traversal (`stock.getProduct().getId()` 등) 정리.
- `StockHistoryResult.from(history, productId)` 외부 주입 패턴 도입.
- test fixture 의 `Stock.builder().product(...)` / `StockHistory.builder().stock(...)` 호출부 정리.
- 루트 `docs/adr.md`, `docs/architecture.md` 동기화.
- 회고록 작성.

### 제외 범위

- **DB schema 변경 / Flyway migration** — 컬럼 (`product_id`, `stock_id`) 은 그대로. JPA 매핑만 해제.
- **DB FK 제약 제거** — `fk_stock_product_id`, `fk_stock_history_stock_id` 유지. 모든 도메인 association 해제 완료 후 별도 트랙에서 일괄 정리.
- **응답 API 계약 정비** — `StockHistoryResult.productId`, `AdminStockResult.productId` 필드는 기존 응답 계약 그대로 유지. echo 응답 정리는 별도 트랙.
- **Order / Payment 도메인** — 후속 sub-PR.
- **같은 aggregate 내 root-child 관계** — 본 태스크 범위 내 해당 사항 없음 (Stock·StockHistory 는 별도 aggregate 로 다룬다).

## 주요 시나리오

- 관리자가 상품의 초기 재고를 생성한다.
- 관리자가 상품 재고를 수동 증가 / 감소한다.
- 관리자가 상품의 재고 변경 이력을 조회한다.
- 주문 / 복구 흐름에서 재고를 차감 / 복구한다.
- 위 시나리오 모두 기존과 동일하게 동작하되, JPA 매핑 차원에서 `Stock.product` / `StockHistory.stock` 객체 참조를 사용하지 않는다.

## 요구사항

- `Stock` 은 `productId: Long` 을 가진다. `@OneToOne` 매핑 제거.
- `StockHistory` 는 `stockId: Long` 을 가진다. `@ManyToOne` 매핑 제거.
- `StockRepository` 의 productId 기반 조회 메서드는 시그니처 유지 (`findByProductId`, `findByProductIdWithPessimisticLock`, `findAllByProductIdInWithPessimisticLock`).
- `StockHistoryRepository` 의 productId 기반 조회는 stockId 기반으로 시그니처 변경 (`findAllByStockProductIdOrderByCreatedAtDesc` → `findAllByStockIdOrderByCreatedAtDesc`).
- `AdminStockService.getHistoriesByProductId` 는 stock 존재 검증으로 얻은 stockId 로 history 를 조회한다.
- `StockHistoryResult.from(StockHistory, Long productId)` 시그니처를 도입해 path / command 의 productId 를 외부 주입한다.
- 기존 `./gradlew test integrationTest` 통과.

## 제약사항

- DB schema (테이블 / 컬럼 / FK) 는 손대지 않는다. Hibernate `validate` 통과 가능해야 한다.
- API 응답 계약 (StockHistoryResult / AdminStockResult 의 필드 구성) 유지.
- 동시성 / 보상 흐름의 회귀 없음 — `StockInventoryService`, `StockConcurrencyService`, stock outbox 흐름 동작 보존.
- ADR-020 의 적용 범위 ("같은 aggregate 내 root-child 는 객체 참조 허용") 를 본 태스크에 그대로 따른다.
