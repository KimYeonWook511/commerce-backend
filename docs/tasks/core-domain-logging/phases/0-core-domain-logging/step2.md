# Step 2: stock-domain-logging

## 읽어야 할 파일

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/logging-conventions.md` (§2 레벨, §7 메시지 패턴)
- `src/main/java/com/commerce/stock/application/StockInventoryService.java`
- `src/main/java/com/commerce/stock/application/AdminStockService.java`
- `src/main/java/com/commerce/stock/domain/Stock.java` — `getQuantity()` 등 노출 메서드 확인

step1에서 변경된 파일:
- `src/main/java/com/commerce/order/application/OrderCancelService.java` — `StockInventoryService.increase` 호출자

## 작업

### 1. `StockInventoryService` — 재고 차감/복구/일괄 차감 INFO

파일: `src/main/java/com/commerce/stock/application/StockInventoryService.java`

- 클래스 상단에 `@Slf4j` 부착
- `decrease(Long productId, int quantity)`: `stock.decrease(quantity)` 직후:
  ```java
  log.info("재고 차감 productId={} quantity={} remaining={}",
      productId, quantity, stock.getQuantity());
  ```
- `increase(Long productId, int quantity)`: `stock.increase(quantity)` 직후:
  ```java
  log.info("재고 복구 productId={} quantity={} remaining={}",
      productId, quantity, stock.getQuantity());
  ```
- `decreaseBatch(StockDecreaseBatchCommand command)`: `return StockDecreaseBatchResult.from(quantitiesByProductId)` 직전:
  ```java
  log.info("재고 일괄 차감 productCount={}", quantitiesByProductId.size());
  ```

### 2. `AdminStockService` — 운영자 재고 관리 INFO

파일: `src/main/java/com/commerce/stock/application/AdminStockService.java`

- 클래스 상단에 `@Slf4j` 부착
- `createInitialStock(AdminStockCreateCommand command)`: `saveHistory(...)` 호출 직후, `return AdminStockResult.from(savedStock)` 직전:
  ```java
  log.info("재고 초기 설정 productId={} quantity={} reason={} adminMemberId={}",
      command.getProductId(), command.getQuantity(), command.getReason(), command.getAdminMemberId());
  ```
- `increaseByAdmin(AdminStockAdjustCommand command)`: `saveHistory(...)` 호출 직후:
  ```java
  log.info("재고 운영 증가 productId={} quantity={} reason={} adminMemberId={} newTotal={}",
      command.getProductId(), command.getQuantity(), command.getReason(), command.getAdminMemberId(), stock.getQuantity());
  ```
- `decreaseByAdmin(AdminStockAdjustCommand command)`: `saveHistory(...)` 호출 직후:
  ```java
  log.info("재고 운영 감소 productId={} quantity={} reason={} adminMemberId={} newTotal={}",
      command.getProductId(), command.getQuantity(), command.getReason(), command.getAdminMemberId(), stock.getQuantity());
  ```
- `getHistoriesByProductId(Long productId)`: 조회 메서드 — INFO 추가하지 않음

## 수정 가능 경로

- `src/main/java/com/commerce/stock/application/StockInventoryService.java`
- `src/main/java/com/commerce/stock/application/AdminStockService.java`
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드 실행 → 기존 테스트 모두 PASS
2. 2개 파일에 `@Slf4j` 부착 확인
3. INFO 로그 메시지가 사전 시그니처와 정확히 일치
4. `StockInventoryService.decrease/increase`의 `remaining`이 `stock.getQuantity()` (즉 변경 후 수량) 사용 확인
5. `AdminStockService.createInitialStock`은 `savedStock.getQuantity()` 또는 `command.getQuantity()`(둘 다 같은 값)이며 `newTotal` 필드 미포함 (초기 설정이므로). 컨벤션상 필드명은 사전 시그니처를 따른다.
6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 단순 조회 메서드(`AdminStockService.getHistoriesByProductId`)에 INFO 추가 금지. 이유: 컨벤션 §3 "유스케이스 시작·완료" 정신과 불일치.
- `OptimisticLockingFailureException`에 대한 catch 또는 `log.warn` 추가 금지. 이유: `GlobalExceptionHandler`가 일괄 WARN 처리 (§4).
- `log.error()` 추가 금지. 이유: 보상 catch 없음.
- 비즈니스 로직 변경 금지.
- 기존 테스트를 깨뜨리지 마라.
