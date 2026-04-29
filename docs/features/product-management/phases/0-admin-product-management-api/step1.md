# Step 1: admin-product-command-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/features/product-management/prd.md`
- `/docs/features/product-management/architecture.md`
- `/docs/features/product-management/adr.md`
- `/docs/features/product-management/api-spec.md`
- `/docs/features/product-management/db-schema.md`
- `/docs/features/product-management/phases/0-admin-product-management-api/step0.md`
- `/docs/architecture.md`
- `/docs/api-spec.md`
- `/src/main/java/com/commerce/product/**`
- `/src/main/java/com/commerce/auth/interceptor/RequireRole.java`
- `/src/main/java/com/commerce/member/domain/MemberRole.java`
- `/src/test/java/com/commerce/auth/controller/AuthWebSecurityTest.java`
- `/src/test/java/com/commerce/order/controller/OrderControllerTest.java`
- `/src/test/java/com/commerce/product/controller/ProductControllerTest.java`

이전 step에서 만들어진 코드와 feature 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

관리자 상품 command API를 구현하라.

- `POST /admin/products`를 추가해 상품을 등록하라.
- `PATCH /admin/products/{productId}`를 추가해 상품명, 가격, 설명, 이미지 URL, 판매 상태를 수정하라.
- `DELETE /admin/products/{productId}`를 추가해 상품을 soft delete 하라.
- 관리자 API 메서드에는 `@RequireRole(MemberRole.ROLE_ADMIN)`을 적용하라.
- request DTO에는 Bean Validation을 적용하라.
- response/result DTO는 엔티티를 직접 노출하지 말고 필요한 필드만 반환하라.
- service에는 등록, 수정, 삭제 메서드를 추가하고 기존 공개 조회 메서드와 책임을 분리하라.
- 삭제되었거나 존재하지 않는 상품을 수정/삭제하려 하면 `ProductException(ProductErrorCode.PRODUCT_NOT_FOUND)`를 사용하라.
- 컨트롤러 테스트와 서비스 테스트를 추가해 등록, 수정, 삭제, 권한 검증, validation 실패를 검증하라.

## 수정 가능 경로

- `src/main/java/com/commerce/product/**`
- `src/test/java/com/commerce/product/**`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.product.*'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 관리자 API가 `ROLE_ADMIN` 권한으로 보호되는가?
   - Controller에 비즈니스 로직이 들어가지 않았는가?
   - 등록/수정/삭제 응답이 `ApiResponse<T>` 형식을 따르는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 파일 업로드를 구현하지 마라. 이유: 이번 feature는 `imageUrl` 문자열 저장만 다룬다.
- 상품 등록 시 재고 레코드를 생성하지 마라. 이유: 초기 재고 정책은 다음 feature에서 결정한다.
- hard delete를 호출하지 마라. 이유: 주문 이력 보존 요구와 충돌한다.
- 기존 테스트를 깨뜨리지 마라
