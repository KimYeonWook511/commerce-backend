# Step 4: admin-stock-history-query-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/features/stock-management/prd.md`
- `docs/features/stock-management/architecture.md`
- `docs/features/stock-management/adr.md`
- `docs/features/stock-management/api-spec.md`
- `docs/features/stock-management/db-schema.md`
- `docs/features/stock-management/phases/0-admin-stock-management-api/step3.md`
- `src/main/java/com/commerce/stock/controller/AdminStockController.java`
- `src/main/java/com/commerce/stock/service/StockService.java`
- `src/main/java/com/commerce/stock/repository/StockHistoryRepository.java`
- `src/test/java/com/commerce/stock/controller/AdminStockControllerTest.java`

기능 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `docs/api-spec.md`
- `docs/architecture.md`

## 작업

- `AdminStockController`에 `GET /admin/products/{productId}/stock/histories` API를 추가한다.
  - `@RequireRole(MemberRole.ROLE_ADMIN)`을 적용한다.
  - `productId`가 null 또는 1 미만이면 기존 `CommonException(CommonErrorCode.INVALID_REQUEST)`를 던진다.
  - 성공 시 `200 OK`와 `ApiResponse<List<AdminStockHistoryResult>>`를 반환한다.
- `StockService`의 이력 조회 결과를 controller 응답에 연결한다.
- 이력 조회는 상품별 최신순 전체 목록을 반환한다.
- controller 테스트를 추가한다.
  - 관리자 이력 조회 성공
  - 관리자 권한이 없으면 실패
  - `productId`가 양수가 아니면 실패
- repository 테스트를 추가한다.
  - 상품별 이력 조회가 다른 상품 이력을 제외하는지
  - 최신순 정렬을 보장하는지

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
2. 아래를 확인한다.
   - 이력 조회 API가 pagination 파라미터를 요구하지 않는가?
   - 응답에 `historyId`, `productId`, `stockId`, `quantityChange`, `reason`, `adminMemberId`, `createdAt`이 포함되는가?
   - 최신순 정렬이 repository 테스트로 검증되는가?

## 금지사항

- 이 단계에서 pagination을 추가하지 마라. 이유: 첫 버전의 조회 범위는 상품별 전체 최신순 조회로 결정했다.
- request body로 `adminMemberId`를 받지 마라. 이유: 변경 주체는 인증 컨텍스트 기반으로 기록한다.
- 이력 데이터를 수정하거나 삭제하는 API를 추가하지 마라. 이유: 이력은 감사 데이터로 append-only 성격을 가진다.
