# Step 3: rename-stock-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 맥락을 파악하라:

- `docs/tasks/application-layer-rename/prd.md`
- `src/main/java/com/commerce/stock/application/service/StockInventoryService.java`
- `src/main/java/com/commerce/stock/application/service/AdminStockService.java`
- `src/main/java/com/commerce/stock/application/service/StockConcurrencyService.java`

## 작업

stock 도메인 Service 클래스를 ADR-054 컨벤션으로 리네임·분리한다.
동작 변경 없이 파일명·클래스명·주입 변수명·테스트명만 바꾼다.

### 리네임 목록

| 현재 | 변경 후 | 처리 방식 |
|---|---|---|
| `StockInventoryService.decrease(...)` | `DecreaseStockService` | 분리 |
| `StockInventoryService.increase(...)` | `IncreaseStockService` | 분리 |
| `StockInventoryService.decreaseBatch(...)` | `DecreaseStockBatchService` | 분리 |
| `AdminStockService.createInitialStock(...)` | `AdminInitializeStockService` | 분리 |
| `AdminStockService.increaseByAdmin(...)` | `AdminIncreaseStockService` | 분리 |
| `AdminStockService.decreaseByAdmin(...)` | `AdminDecreaseStockService` | 분리 |
| `AdminStockService.getHistoriesByProductId(...)` | `AdminGetStockHistoryService` | 분리 |
| `StockConcurrencyService` | `StockDecreaseConcurrencyService` | 리네임 |

### 절차

1. 각 대상 클래스를 사용하는 모든 파일을 확인한다.

   ```bash
   grep -rl "StockInventoryService\|AdminStockService\|StockConcurrencyService" src/
   ```

2. **StockInventoryService 분리**: `decrease`, `increase`, `decreaseBatch` 메서드를 각각 별도 파일로 분리한다. 기존 파일 삭제.

3. **AdminStockService 분리**: 4개 메서드를 각각 별도 파일로 분리한다. 기존 파일 삭제.

4. **StockConcurrencyService 리네임**: `StockConcurrencyService.java` → `StockDecreaseConcurrencyService.java`. 내부 메서드는 그대로 유지한다.

5. 모든 참조 파일에서 업데이트한다:
   - 단일 `StockInventoryService` 주입 → `DecreaseStockService`, `IncreaseStockService`, `DecreaseStockBatchService` 개별 주입
   - 단일 `AdminStockService` 주입 → 4개 클래스 개별 주입
   - `StockConcurrencyService` → `StockDecreaseConcurrencyService`
   - 변수명은 새 클래스명의 camelCase를 따른다

6. 테스트 파일에서 클래스명·메서드명·`@DisplayName`을 새 이름 기준으로 갱신한다.

### 주의사항

- `StockInventoryService.decrease`와 `AdminStockService.decreaseByAdmin`은 서로 다른 클래스에서 왔다. 각각 `DecreaseStockService`와 `AdminDecreaseStockService`로 독립 분리한다. 통합하지 마라.
- `StockDecreaseConcurrencyService` 내부의 동시성 테스트용 메서드(`decreaseWithSynchronized`, `decreaseWithOptimisticLock` 등)는 그대로 유지한다. 이름을 통일하거나 정리하지 마라.

### 금지사항

- 분리 클래스의 메서드 내부 로직을 변경하지 마라. 이유: 동작 불변 원칙.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - 구 클래스명이 `src/` 하위에 남아 있지 않은가.
     ```bash
     grep -r "StockInventoryService\|AdminStockService\b\|StockConcurrencyService\b" src/
     ```
   - 분리된 클래스 각각이 정확히 1개의 public 메서드(또는 동시성 전략 묶음)만 보유하는가.
