# Step 2: domain-history-model

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/features/stock-management/prd.md`
- `docs/features/stock-management/architecture.md`
- `docs/features/stock-management/adr.md`
- `docs/features/stock-management/api-spec.md`
- `docs/features/stock-management/db-schema.md`
- `src/main/java/com/commerce/stock/domain/Stock.java`
- `src/main/java/com/commerce/stock/exception/StockErrorCode.java`
- `src/test/java/com/commerce/stock/domain/StockTest.java`

기능 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `docs/architecture.md`
- `docs/adr.md`
- `docs/db-schema.md`

## 작업

- `stock` 도메인에 재고 변경 사유 enum `StockAdjustmentReason`을 추가한다.
  - 값은 `INBOUND`, `DISPOSAL`, `ADMIN_ADJUSTMENT`, `ORDER_CANCEL_RESTORE`이다.
- 신규 엔티티 `StockHistory`를 추가한다.
  - 테이블명은 `tbl_stock_history`이다.
  - 필드는 `id`, `stock`, `quantityChange`, `reason`, `adminMemberId`를 포함한다.
  - `BaseTimeEntity`를 상속해 `createdAt`, `updatedAt`을 기록한다.
  - `stock`은 `Stock`과 `ManyToOne(fetch = LAZY)` 관계로 둔다.
  - `quantityChange`, `reason`, `adminMemberId`는 null이 아니어야 한다.
- `StockHistory` 생성 시 `quantityChange`가 0이면 실패하도록 도메인 검증을 둔다.
- stock 예외 체계에 이 단계에서 필요한 신규 예외 코드가 있으면 추가한다.
  - 이미 존재하는 stock 예외 코드와 코드 문자열이 충돌하지 않게 한다.
- `StockHistoryRepository`는 이 단계에서 만들지 않는다. repository와 조회는 후속 step에서 다룬다.
- 도메인 테스트를 추가한다.
  - 양수 변경 수량 이력 생성
  - 음수 변경 수량 이력 생성
  - 0 변경 수량 실패

## 수정 가능 경로

- `src/main/java/com/commerce/stock/domain/**`
- `src/main/java/com/commerce/stock/exception/**`
- `src/test/java/com/commerce/stock/domain/**`
- `docs/features/stock-management/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `StockHistory`가 `tbl_stock_history`와 매핑되는가?
   - 이력 변경 수량 0을 허용하지 않는가?
   - `StockAdjustmentReason` 값이 API 스펙과 일치하는가?
   - 기존 `StockTest`가 깨지지 않는가?

## 금지사항

- 관리자 API를 이 step에서 만들지 마라. 이유: domain model 검증과 API 연결 책임을 분리한다.
- `Stock`의 기존 `decrease`, `increase` 동작을 바꾸지 마라. 이유: 주문 경로의 기존 재고 차감/복구 동작에 영향을 줄 수 있다.
- 기존 테스트를 삭제하거나 완화하지 마라. 이유: 주문 재고 정합성 회귀를 놓칠 수 있다.
