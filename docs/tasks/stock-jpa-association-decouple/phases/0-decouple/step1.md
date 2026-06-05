# Step 1: stock-association-decouple

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/stock-jpa-association-decouple/prd.md`
- `/docs/tasks/stock-jpa-association-decouple/architecture.md`
- `/docs/tasks/stock-jpa-association-decouple/adr.md`
- `/docs/tasks/stock-jpa-association-decouple/api-spec.md`
- `/docs/tasks/stock-jpa-association-decouple/db-schema.md`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/adr.md` (ADR-020 — 신규 도메인 cross-aggregate ID 참조, ADR-011 — find-first 패턴)
- `/docs/architecture.md`
- `/docs/tasks/cart/adr.md` (ADR-020 의 최초 적용 사례)

현재 코드 구조를 파악하기 위해 아래 파일도 읽는다.

- `/src/main/java/com/commerce/stock/domain/Stock.java`
- `/src/main/java/com/commerce/stock/domain/StockHistory.java`
- `/src/main/java/com/commerce/stock/domain/repository/StockRepository.java`
- `/src/main/java/com/commerce/stock/domain/repository/StockHistoryRepository.java`
- `/src/main/java/com/commerce/stock/infrastructure/JpaStockRepository.java`
- `/src/main/java/com/commerce/stock/infrastructure/JpaStockHistoryRepository.java`
- `/src/main/java/com/commerce/stock/infrastructure/StockRepositoryAdapter.java`
- `/src/main/java/com/commerce/stock/infrastructure/StockHistoryRepositoryAdapter.java`
- `/src/main/java/com/commerce/stock/application/AdminStockService.java`
- `/src/main/java/com/commerce/stock/application/StockInventoryService.java`
- `/src/main/java/com/commerce/stock/application/StockConcurrencyService.java`
- `/src/main/java/com/commerce/stock/application/result/AdminStockResult.java`
- `/src/main/java/com/commerce/stock/application/result/StockHistoryResult.java`

## 작업

Stock / StockHistory 도메인의 JPA cross-aggregate association 을 해제하고 `Long` ID 필드로 전환한다. ADR-020 의 후속 트랙.

### 도메인 변경

- `Stock`
  - `@OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id", ..., foreignKey = @ForeignKey(name = "fk_stock_product_id")) Product product` 필드 제거.
  - `@Column(name = "product_id", nullable = false) Long productId` 필드 추가.
  - builder 인자 `product(Product)` → `productId(Long)`.
- `StockHistory`
  - `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "stock_id", ..., foreignKey = @ForeignKey(name = "fk_stock_history_stock_id")) Stock stock` 필드 제거.
  - `@Column(name = "stock_id", nullable = false) Long stockId` 필드 추가.
  - builder 인자 `stock(Stock)` → `stockId(Long)`.
- 기존 `@Version`, `BaseTimeEntity`, `decrease` / `increase` 도메인 메서드, 검증 로직, exception 흐름은 그대로 유지한다.

### Repository 변경

- `StockHistoryRepository` (domain 인터페이스)
  - `findAllByStockProductIdOrderByCreatedAtDesc(Long productId)` → `findAllByStockIdOrderByCreatedAtDesc(Long stockId)` 로 시그니처 변경.
- `JpaStockHistoryRepository`
  - 동일 시그니처 변경. derived query 가 `stock_id` 기준으로 조회.
- `StockHistoryRepositoryAdapter` — 위 시그니처 변경 반영.
- `JpaStockRepository`
  - JPQL 의 `s.product.id = :productId` → `s.productId = :productId` 로 변경.
  - `findAllByProductIdInWithPessimisticLock` 의 `s.product.id in :productIds` → `s.productId in :productIds` 로 변경.
  - `findByProductId(Long productId)` derived query 는 `Stock.productId` 필드 기반으로 자연 매핑되므로 시그니처 유지.

### Application 변경

- `AdminStockService`
  - `createInitialStock`
    - product 존재 검증 후 `Stock.builder().productId(product.getId()).quantity(...).build()` 로 빌드.
    - `saveHistory(savedStock, ...)` 호출부도 stockId 기반으로 변경 (아래 saveHistory 시그니처 참고).
  - `increaseByAdmin`, `decreaseByAdmin` — `saveHistory(stock, ...)` 호출부를 stockId 기반으로 변경.
  - `getHistoriesByProductId(Long productId)`
    - stock 존재 검증으로 얻은 `stock.getId()` (= stockId) 로 `findAllByStockIdOrderByCreatedAtDesc(stockId)` 호출.
    - history 매핑 시 `StockHistoryResult.from(history, productId)` 로 path productId 외부 주입.
  - `saveHistory(Stock stock, int quantityChange, StockAdjustmentReason reason, Long adminMemberId)` → `saveHistory(Long stockId, int quantityChange, StockAdjustmentReason reason, Long adminMemberId)` 로 시그니처 변경. `StockHistory.builder().stockId(stockId).quantityChange(...).reason(...).adminMemberId(...).build()` 사용.
- `StockInventoryService.decreaseBatch`
  - `stocksByProductId = findStocks.stream().collect(Collectors.toMap(stock -> stock.getProduct().getId(), Function.identity()))` → `stock -> stock.getProductId()` 로 변경.
- `StockConcurrencyService` — `Stock` builder 호출 없음. 구조 변경 불필요.

### Result DTO 변경

- `AdminStockResult.from(Stock stock)` — 시그니처 유지. 내부 `stock.getProduct().getId()` → `stock.getProductId()` 로 변경.
- `StockHistoryResult`
  - 정적 팩토리 메서드 시그니처를 `from(StockHistory history)` → `from(StockHistory history, Long productId)` 로 변경.
  - 응답 필드 구성 (`historyId`, `productId`, `stockId`, `quantityChange`, `reason`, `adminMemberId`, `createdAt`) 유지.
  - 내부 productId 매핑은 외부 주입된 `productId` 인자 사용.
  - 내부 stockId 매핑은 `history.getStockId()` 직접 사용.

### Test fixture 변경

- 모든 `Stock.builder().product(product)` 호출부를 `Stock.builder().productId(product.getId())` 로 갱신한다.
- 모든 `StockHistory.builder().stock(stock)` 호출부를 `StockHistory.builder().stockId(stock.getId())` 로 갱신한다.
- 영향 파일 (grep 결과 기준 주요 위치):
  - `/src/test/java/com/commerce/stock/domain/StockTest.java`
  - `/src/test/java/com/commerce/stock/domain/StockHistoryTest.java`
  - `/src/test/java/com/commerce/stock/application/AdminStockServiceTest.java`
  - `/src/test/java/com/commerce/stock/application/StockInventoryServiceTest.java`
  - `/src/test/java/com/commerce/stock/application/StockConcurrencyServiceTest.java`
  - `/src/test/java/com/commerce/stock/application/concurrency/StockConcurrencyTest.java`
  - `/src/test/java/com/commerce/stock/infrastructure/StockRepositoryJpaAdapterTest.java`
  - `/src/test/java/com/commerce/stock/infrastructure/StockHistoryRepositoryJpaAdapterTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateServiceConcurrencyTest.java`
  - `/src/test/java/com/commerce/order/application/OrderCreateCartIntegrationTest.java`
  - `/src/test/java/com/commerce/order/application/OrderApplicationServiceIntegrationTest.java`
  - `/src/test/java/com/commerce/order/application/concurrency/OrderConcurrencyServiceTest.java`
  - `/src/test/java/com/commerce/order/application/concurrency/OrderConcurrencyServiceDebugTest.java`
  - `/src/test/java/com/commerce/order/application/concurrency/OrderConcurrencyServiceDeadlockTest.java`
  - `/src/test/java/com/commerce/order/infrastructure/persistence/concurrency/OrderConcurrencyServiceDeadlockMysqlTest.java`
  - `/src/test/java/com/commerce/product/application/ProductQueryServiceTest.java`
- 위 목록은 참고용이며, 실제 변경 시 추가 호출부가 컴파일 오류로 드러나면 모두 갱신한다.

### DB schema / Flyway

- 변경 없음. Flyway migration 파일 추가하지 않는다.
- DB FK 제약 (`fk_stock_product_id`, `fk_stock_history_stock_id`) 그대로 유지.

## 수정 가능 경로

- `src/main/java/com/commerce/stock/**`
- `src/test/java/com/commerce/stock/**`
- `src/test/java/com/commerce/order/**` (stock fixture 호출부 한정)
- `src/test/java/com/commerce/product/**` (stock fixture 호출부 한정)
- `docs/tasks/stock-jpa-association-decouple/**`

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `Stock` / `StockHistory` 에서 `@OneToOne` / `@ManyToOne` / `@JoinColumn` import 가 모두 제거됐는가?
   - `Product`, `Stock` 객체 참조가 entity 와 application 코드에서 traverse 형태로 남아있지 않는가? — `rg "stock\.getProduct\(\)" src/` 결과 0건.
   - `rg "history\.getStock\(\)" src/` 결과 0건 (test fixture 의 의도적 호출 제외 시 0건).
   - `JpaStockRepository` 의 JPQL 이 `s.productId = :productId` / `s.productId in :productIds` 형태인가?
   - `JpaStockHistoryRepository` 의 derived query 가 `findAllByStockIdOrderByCreatedAtDesc` 인가?
   - `AdminStockService.getHistoriesByProductId` 가 `StockHistoryResult.from(history, productId)` 외부 주입을 사용하는가?
   - DB schema 변경 / Flyway V 파일 추가가 없는가? — `git diff src/main/resources/db/migration/` 결과 없음.
   - architecture.md 의 디렉토리 구조와 컨벤션을 따랐는가?
   - ADR-020 / ADR-011 등 상위 작업 규칙을 위반하지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- StockHistory 에 `productId` 필드를 추가하지 마라. 이유: ADR (결정 2) 가 application 외부 주입 패턴을 채택했다. 도메인 본질이 아닌 정보를 schema 에 박지 않는다.
- Flyway V 파일을 추가하지 마라. 이유: 본 sub-PR series 의 메타 원칙은 schema 변경 0건이고, FK 제거는 별도 트랙이다.
- DB FK 제약 (`fk_stock_product_id`, `fk_stock_history_stock_id`) 을 제거하지 마라. 이유: 별도 트랙. JPA 매핑 차원에서만 association 해제한다.
- `StockHistoryResult` / `AdminStockResult` 의 응답 필드 구성을 변경하지 마라. 이유: 본 sub-PR 의 정책 목적은 association 해제이지 응답 계약 정비가 아니다.
- fetch join 대체 DTO projection 패턴을 새로 도입하지 마라. 이유: Stock 도메인에는 fetch join 사용처가 없고, 도입은 후속 Order sub-PR 의 결정 범위다.
- 기존 테스트를 깨뜨리지 마라.
