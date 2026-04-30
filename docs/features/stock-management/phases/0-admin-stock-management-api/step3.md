# Step 3: admin-stock-adjust-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/features/stock-management/prd.md`
- `docs/features/stock-management/architecture.md`
- `docs/features/stock-management/adr.md`
- `docs/features/stock-management/api-spec.md`
- `docs/features/stock-management/db-schema.md`
- `docs/features/stock-management/phases/0-admin-stock-management-api/step2.md`
- `src/main/java/com/commerce/stock/controller/AdminStockController.java`
- `src/main/java/com/commerce/stock/service/StockService.java`
- `src/test/java/com/commerce/stock/controller/AdminStockControllerTest.java`

기능 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `docs/api-spec.md`
- `docs/architecture.md`

## 작업

- `AdminStockController`에 재고 증가/감소 API를 추가한다.
  - `POST /admin/products/{productId}/stock/increase`
  - `POST /admin/products/{productId}/stock/decrease`
- 두 API 모두 아래 정책을 따른다.
  - `@RequireRole(MemberRole.ROLE_ADMIN)`을 적용한다.
  - `@AuthenticatedMemberId Long adminMemberId`를 받아 service command에 전달한다.
  - `productId`가 null 또는 1 미만이면 기존 `CommonException(CommonErrorCode.INVALID_REQUEST)`를 던진다.
  - 성공 시 `200 OK`와 `ApiResponse<AdminStockResult>`를 반환한다.
- 증가/감소 request DTO를 추가한다.
  - `quantity`: 필수, 양수
  - `reason`: 필수, `INBOUND|DISPOSAL|ADMIN_ADJUSTMENT|ORDER_CANCEL_RESTORE`
  - request DTO에서 service command로 변환한다.
- controller 테스트를 추가한다.
  - 관리자 재고 증가 성공
  - 관리자 재고 감소 성공
  - 관리자 권한이 없으면 실패
  - `productId`가 양수가 아니면 실패
  - `quantity` 또는 `reason` 검증 실패

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
   - 증가와 감소 endpoint가 분리되어 있는가?
   - 감소 API가 음수 quantity를 받는 방식으로 구현되지 않았는가?
   - 성공 응답 HTTP status가 `200 OK`인가?
   - 요청 검증 메시지가 기존 controller 테스트 스타일과 일관적인가?

## 금지사항

- 증가/감소를 하나의 delta API로 합치지 마라. 이유: 사용자가 증가/감소 API 분리를 선택했다.
- request body의 `quantity`에 음수를 허용하지 마라. 이유: API 동작은 endpoint로 구분하고 이력 부호는 service가 결정한다.
- 기존 주문 재고 차감 API나 service 호출부를 수정하지 마라. 이유: 관리자 수동 조정과 주문 흐름은 분리한다.
