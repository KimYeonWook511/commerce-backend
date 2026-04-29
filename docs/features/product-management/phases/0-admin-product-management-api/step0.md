# Step 0: product-domain-fields

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/features/product-management/prd.md`
- `/docs/features/product-management/architecture.md`
- `/docs/features/product-management/adr.md`
- `/docs/features/product-management/api-spec.md`
- `/docs/features/product-management/db-schema.md`
- `/docs/architecture.md`
- `/docs/api-spec.md`
- `/docs/db-schema.md`
- `/src/main/java/com/commerce/product/domain/Product.java`
- `/src/main/java/com/commerce/product/repository/ProductRepository.java`
- `/src/main/java/com/commerce/product/service/ProductService.java`
- `/src/test/java/com/commerce/product/service/ProductServiceTest.java`

이전 step에서 만들어진 코드와 feature 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`product` 도메인에 상품 관리 필드와 공개 조회 노출 정책을 추가하라.

- `ProductStatus` enum을 추가하고 `ON_SALE`, `SOLD_OUT`, `STOPPED` 값을 정의하라.
- `Product`에 `description`, `imageUrl`, `status`, `deletedAt` 필드를 추가하라.
- `Product` 생성 시 `name`, `price`, `status`는 필수로 다루고 `description`, `imageUrl`은 null 허용 값으로 다루라.
- `Product`에 수정용 도메인 메서드와 soft delete용 도메인 메서드를 추가하라.
- 가격은 0보다 커야 한다는 도메인 검증을 추가하라.
- 공개 조회 대상은 삭제되지 않았고 `status`가 `ON_SALE` 또는 `SOLD_OUT`인 상품으로 제한하라.
- 기존 `ProductService.getProducts()`와 `ProductService.getProduct(Long productId)`가 위 공개 조회 정책을 따르도록 수정하라.
- 상세 조회에서 재고 레코드가 없으면 기존처럼 `stockQuantity=0`을 유지하라.
- 공개 조회용 result DTO가 새 필드를 꼭 포함할 필요는 없다. 기존 공개 응답 계약을 유지하라.
- 도메인 테스트와 서비스 테스트를 추가 또는 수정해 상태별 공개 조회, soft delete 제외, 가격 검증을 검증하라.

## 수정 가능 경로

- `src/main/java/com/commerce/product/**`
- `src/test/java/com/commerce/product/**`
- `docs/features/product-management/**`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.product.*'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - architecture.md 디렉토리 구조를 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - 공개 상품 조회에서 `STOPPED`와 삭제 상품이 제외되는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 재고 레코드를 생성하지 마라. 이유: 재고 관리는 다음 feature에서 별도로 다룬다.
- 공개 조회 응답에 관리자 전용 필드를 임의로 추가하지 마라. 이유: 기존 공개 API 계약을 유지해야 한다.
- hard delete를 구현하지 마라. 이유: 주문 이력 보존 요구와 충돌한다.
- 기존 테스트를 깨뜨리지 마라
