# Step 3: stock-command-service

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/features/stock-management/prd.md`
- `docs/features/stock-management/architecture.md`
- `docs/features/stock-management/adr.md`
- `docs/features/stock-management/api-spec.md`
- `docs/features/stock-management/db-schema.md`
- `docs/features/stock-management/phases/0-admin-stock-management-api/step1.md`
- `src/main/java/com/commerce/stock/domain/Stock.java`
- `src/main/java/com/commerce/stock/domain/StockHistory.java`
- `src/main/java/com/commerce/stock/repository/StockRepository.java`
- `src/main/java/com/commerce/stock/service/StockService.java`
- `src/main/java/com/commerce/product/repository/ProductRepository.java`
- `src/test/java/com/commerce/stock/service/StockServiceTest.java`

기능 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `docs/architecture.md`
- `docs/adr.md`

## 작업

- `StockHistoryRepository`를 추가한다.
  - 상품별 이력 조회를 위해 `findAllByStockProductIdOrderByCreatedAtDesc(Long productId)` 형태의 메서드를 제공한다.
- 관리자 재고 command/result DTO를 `stock.service.command`, `stock.service.result` 아래에 추가한다.
  - 초기 재고 생성 command: `productId`, `quantity`, `reason`, `adminMemberId`
  - 재고 증가/감소 command: `productId`, `quantity`, `reason`, `adminMemberId`
  - 재고 변경 result: `productId`, `stockId`, `quantity`
  - 이력 result: `historyId`, `productId`, `stockId`, `quantityChange`, `reason`, `adminMemberId`, `createdAt`
- `StockService`에 관리자 재고 메서드를 추가한다.
  - 초기 재고 생성:
    - `ProductRepository.findByIdAndDeletedAtIsNull`로 상품을 확인한다.
    - `StockRepository.findByProductId`로 기존 재고 존재 여부를 확인한다.
    - 재고가 이미 있으면 신규 stock 예외를 던진다.
    - `quantity`는 0 이상이어야 한다.
    - `Stock` 저장 후 `quantityChange = quantity` 이력을 저장한다.
  - 재고 증가:
    - `StockRepository.findByProductIdWithPessimisticLock`으로 재고를 조회한다.
    - `Stock.increase(quantity)`를 사용한다.
    - `quantityChange = quantity` 이력을 저장한다.
  - 재고 감소:
    - `StockRepository.findByProductIdWithPessimisticLock`으로 재고를 조회한다.
    - `Stock.decrease(quantity)`를 사용한다.
    - `quantityChange = -quantity` 이력을 저장한다.
  - 이력 조회:
    - 재고 존재 여부를 확인한 뒤 상품별 이력을 최신순으로 반환한다.
- 기존 주문 경로에서 사용하는 `decreaseWithPessimisticLock`, `increaseWithPessimisticLock`, `decreaseBatchWithPessimisticLock` 동작은 유지한다.
- service 단위 테스트를 추가한다.
  - 초기 재고 생성 성공
  - 삭제되지 않은 상품이 없으면 상품 없음 실패
  - 이미 재고가 있으면 실패
  - 증가 성공과 양수 이력 저장
  - 감소 성공과 음수 이력 저장
  - 재고 부족 감소 실패
  - 이력 조회 최신순 result 반환

## 수정 가능 경로

- `src/main/java/com/commerce/stock/**`
- `src/test/java/com/commerce/stock/**`
- `docs/features/stock-management/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래 탐색도 함께 수행해 기존 사용처가 깨지지 않았는지 확인한다.

```bash
rg "decreaseWithPessimisticLock|increaseWithPessimisticLock|decreaseBatchWithPessimisticLock" src/main/java src/test/java
```

3. 아래를 확인한다.
   - 관리자 수동 조정은 비관적 락 조회를 사용하는가?
   - 증가/감소 이력의 부호가 API 스펙과 일치하는가?
   - 주문 경로 service 메서드 시그니처를 바꾸지 않았는가?

## 금지사항

- 주문 생성/취소 service를 이 step에서 수정하지 마라. 이유: 관리자 재고관리와 주문 workflow 변경을 분리한다.
- 관리자 controller를 이 step에서 만들지 마라. 이유: service 행위 검증 후 API를 연결한다.
- `ProductRepository`에 불필요한 신규 조회 메서드를 추가하지 마라. 이유: 기존 `findByIdAndDeletedAtIsNull`로 요구사항을 충족할 수 있다.
