# 태스크 아키텍처

## 개요

- Stock / StockHistory 도메인의 JPA Entity 간 cross-aggregate association 을 해제한다.
- 변경 대상은 JPA 매핑 레벨이며, DB schema (컬럼·FK) 와 응답 계약은 그대로 유지한다.
- 후속 sub-PR (`order-jpa-association-decouple`, `payment-jpa-association-decouple`) 의 baseline 이 된다.

## 변경 대상

### Domain 레이어

- `com.commerce.stock.domain.Stock`
  - `@OneToOne Product product` 제거.
  - `@Column(name = "product_id") Long productId` 추가.
  - builder 인자 `product(Product)` → `productId(Long)`.
- `com.commerce.stock.domain.StockHistory`
  - `@ManyToOne Stock stock` 제거.
  - `@Column(name = "stock_id") Long stockId` 추가.
  - builder 인자 `stock(Stock)` → `stockId(Long)`.

### Repository 레이어

- `com.commerce.stock.domain.repository.StockHistoryRepository`
  - `findAllByStockProductIdOrderByCreatedAtDesc(Long productId)` → `findAllByStockIdOrderByCreatedAtDesc(Long stockId)`.
- `com.commerce.stock.infrastructure.JpaStockHistoryRepository`
  - 동일 시그니처 변경. derived query 가 `stock_id` 컬럼 기준으로 정렬 조회.
- `com.commerce.stock.infrastructure.JpaStockRepository`
  - JPQL `s.product.id = :productId` → `s.productId = :productId`.

### Application 레이어

- `com.commerce.stock.application.AdminStockService`
  - `createInitialStock` 에서 product 존재 검증 후 `Stock.builder().productId(product.getId())` 로 빌드.
  - `saveHistory(Stock, ...)` → `saveHistory(Long stockId, ...)` 로 시그니처 정리.
  - `getHistoriesByProductId(Long productId)` 는 stock 존재 검증으로 얻은 `stock.getId()` 로 `findAllByStockIdOrderByCreatedAtDesc` 호출. result 매핑 시 path productId 를 외부 주입.
- `com.commerce.stock.application.StockInventoryService`
  - `decreaseBatch` 의 `stock.getProduct().getId()` → `stock.getProductId()` 로 치환.
- `com.commerce.stock.application.result.AdminStockResult`
  - `from(Stock stock)` 시그니처 유지. 내부 `stock.getProduct().getId()` → `stock.getProductId()`.
- `com.commerce.stock.application.result.StockHistoryResult`
  - `from(StockHistory history)` → `from(StockHistory history, Long productId)` 시그니처 변경. productId 는 호출자가 명시 주입.

### Test

- 모든 `Stock.builder().product(...)` 호출부를 `Stock.builder().productId(...)` 로 갱신.
- 모든 `StockHistory.builder().stock(...)` 호출부를 `StockHistory.builder().stockId(...)` 로 갱신.
- 도메인 unit test, repository slice test, application test, integrationTest 전반 영향.
- 다른 도메인 (Order 등) 의 stock fixture 도 같은 builder 시그니처 갱신 (컴파일 의존으로 불가피).

## 설계 방향

### Cross-aggregate ID 참조

- ADR-020 의 cross-aggregate ID 참조 원칙을 Stock / StockHistory aggregate 에 적용.
- Stock 과 StockHistory 는 별도 aggregate 로 다룬다. StockHistory 는 audit 도메인이고 Stock 의 lifecycle 에 종속되지 않는다.
- StockHistory 는 stockId 만 들고 다닌다. productId 는 audit aggregate 의 본질이 아니라 외부 컨텍스트 (현재 endpoint 의 path productId) 다.

### 응답 조립 패턴

- application 계층이 응답을 명시 조립한다.
- `StockHistoryResult.from(history, productId)` 는 audit row (`history`) + 외부 컨텍스트 (`productId`) 를 application 이 의도적으로 조립한다는 의도를 코드 표면에 드러낸다.
- entity 객체 traversal (`history.getStock().getProduct().getId()`) 같은 "domain 그래프 == 응답 모델" 결합을 끊는다. ADR-020 통증 #1 ("편한 탐색 오용") 해소.

### Stock 존재 검증

- `AdminStockService.getHistoriesByProductId` 는 stock 존재 검증을 먼저 수행한다 (기존과 동일).
- 그 결과로 얻은 `stock.getId()` (= stockId) 를 history 조회 키로 사용한다. productId 와 stockId 사이의 매핑 정합성은 `uk_stock_product_id` unique 제약으로 보장된다.

## 데이터 흐름

### 관리자 재고 이력 조회 (`GET /admin/products/{productId}/stock/histories`)

```
Controller
  validateProductId(productId)
  → AdminStockService.getHistoriesByProductId(productId)
       stock = stockRepository.findByProductId(productId)  // 존재 검증
       histories = stockHistoryRepository.findAllByStockIdOrderByCreatedAtDesc(stock.getId())
       return histories.map(h -> StockHistoryResult.from(h, productId))   // path productId 외부 주입
  → ApiResponse.of(results)
```

### 관리자 재고 생성 / 증감 (`POST /admin/products/{productId}/stock` 외)

```
Controller
  → AdminStockService.createInitialStock(command)
       product = productRepository.findNotDeletedProduct(command.getProductId())  // 존재 검증
       (중복 검증)
       stock = Stock.builder().productId(product.getId()).quantity(...).build()
       savedStock = stockRepository.save(stock)
       saveHistory(savedStock.getId(), ...)
       return AdminStockResult.from(savedStock)   // savedStock.getProductId() 직접 사용
```

### 주문 / 보상 흐름의 재고 차감 (`StockInventoryService.decreaseBatch`)

```
findAllByProductIdInWithPessimisticLock(productIds)
  → stocksByProductId = stocks.toMap(s -> s.getProductId())   // 객체 traversal 제거
  → for each productId: stock.decrease(qty)
```

## 예외 및 실패 처리

- Stock 존재 검증 실패 → `StockException(STOCK_NOT_FOUND)` 유지.
- Product 존재 검증 실패 → `ProductException(PRODUCT_NOT_FOUND)` 유지.
- 재고 부족 / 잘못된 수량 → 기존 도메인 예외 흐름 유지.
- DB unique / FK 위반은 본 태스크 변경 대상 아님 → 안전망 500 위임 (`ADR-011`).

## 테스트 포인트

- `Stock` / `StockHistory` 단위 테스트 — builder 시그니처 변경 후 도메인 메서드 동작 유지.
- `StockRepositoryJpaAdapterTest`, `StockHistoryRepositoryJpaAdapterTest` — JPQL / derived query 시그니처 변경 후 조회 결과 동일.
- `AdminStockServiceTest` — `saveHistory` 시그니처 변경, history 의 stockId 보존 확인.
- `StockInventoryServiceTest` — batch decrease 의 productId 매핑 보존.
- `StockConcurrencyServiceTest`, `StockConcurrencyTest` — 비관적 락 / Optimistic lock retry 회귀 없음.
- 다른 도메인 테스트 (`OrderCreateServiceConcurrencyTest`, `OrderApplicationServiceIntegrationTest` 등) 의 stock fixture 빌드 호환.
- `./gradlew test integrationTest` 통과 — Hibernate `validate` 통과 확인 포함.
