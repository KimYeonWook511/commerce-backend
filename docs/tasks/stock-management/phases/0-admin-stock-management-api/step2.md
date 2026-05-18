# Step 2: admin-stock-create-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/features/stock-management/prd.md`
- `docs/features/stock-management/architecture.md`
- `docs/features/stock-management/adr.md`
- `docs/features/stock-management/api-spec.md`
- `docs/features/stock-management/db-schema.md`
- `docs/features/stock-management/phases/0-admin-stock-management-api/step1.md`
- `src/main/java/com/commerce/product/controller/AdminProductController.java`
- `src/test/java/com/commerce/product/controller/AdminProductControllerTest.java`
- `src/main/java/com/commerce/auth/resolver/AuthenticatedMemberId.java`
- `src/main/java/com/commerce/auth/interceptor/RequireRole.java`

기능 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `docs/api-spec.md`
- `docs/architecture.md`

## 작업

- `AdminStockController`를 `stock.controller` 아래에 추가한다.
- `POST /admin/products/{productId}/stock` API를 구현한다.
  - `@RequireRole(MemberRole.ROLE_ADMIN)`을 적용한다.
  - `@AuthenticatedMemberId Long adminMemberId`를 받아 service command에 전달한다.
  - `productId`가 null 또는 1 미만이면 기존 `CommonException(CommonErrorCode.INVALID_REQUEST)`를 던진다.
  - 성공 시 `201 Created`와 `ApiResponse<AdminStockResult>`를 반환한다.
- 초기 재고 생성 request DTO를 추가한다.
  - `quantity`: 필수, 0 이상
  - `reason`: 필수, `INBOUND|DISPOSAL|ADMIN_ADJUSTMENT|ORDER_CANCEL_RESTORE`
  - request DTO에서 service command로 변환한다.
- controller 테스트를 추가한다.
  - 관리자 초기 재고 생성 성공
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
   - 관리자 권한 인터셉터와 인증 member id resolver가 controller 테스트에 연결되었는가?
   - 성공 응답 HTTP status가 `201 Created`인가?
   - `ApiResponse<T>` 응답 형식을 유지하는가?

## 금지사항

- 상품 등록 API 요청에 `initialStockQuantity`를 추가하지 마라. 이유: 재고 생성은 별도 API로 결정했다.
- controller에 재고 변경 비즈니스 로직을 넣지 마라. 이유: Controller는 요청 검증과 서비스 위임만 담당한다.
- 이력 조회 API를 이 step에서 만들지 마라. 이유: create API 검증과 query API를 분리한다.
