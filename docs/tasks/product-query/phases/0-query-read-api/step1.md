# Step 2: product-query-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/features/product-query/prd.md`
- `/docs/features/product-query/architecture.md`
- `/docs/features/product-query/adr.md`
- `/docs/features/product-query/api-spec.md`
- `/docs/features/product-query/db-schema.md`
- `/docs/architecture.md`
- `/docs/api-spec.md`
- `/src/main/java/com/commerce/product/domain/Product.java`
- `/src/main/java/com/commerce/product/repository/ProductRepository.java`
- `/src/main/java/com/commerce/stock/repository/StockRepository.java`
- `/src/main/java/com/commerce/order/controller/OrderController.java`
- `/src/main/java/com/commerce/payment/controller/PaymentController.java`
- `/src/test/java/com/commerce/order/controller/OrderControllerTest.java`
- `/src/test/java/com/commerce/payment/controller/PaymentControllerTest.java`

이전 step에서 만들어진 코드와 feature 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`product` 도메인에 공개 조회 API를 구현하라.

- `GET /products` 목록 조회와 `GET /products/{productId}` 상세 조회를 제공하는 controller를 추가하라.
- controller는 인증 없이 동작해야 하며 `ApiResponse<T>` 형식으로 응답해야 한다.
- service는 목록 조회와 상세 조회를 분리된 메서드로 제공하라.
- 목록 조회는 `Product.createdAt DESC` 기준 최신순으로 전체 상품을 조회하고 `productId`, `name`, `price`만 반환하라.
- 상세 조회는 상품 조회 후 `StockRepository`로 재고를 확인해 `productId`, `name`, `price`, `stockQuantity`를 반환하라.
- 상세 조회에서 상품이 없으면 `ProductException(ProductErrorCode.PRODUCT_NOT_FOUND)`를 사용하라.
- 상세 조회에서 재고 레코드가 없으면 `stockQuantity=0`으로 정규화하라.
- 목록/상세 조회용 result DTO를 분리하고 엔티티를 직접 응답으로 노출하지 마라.
- 서비스 테스트와 컨트롤러 테스트를 추가해 정렬, 공개 접근, 상세 재고, 없는 상품 예외를 검증하라.

## 수정 가능 경로

- `src/main/java/com/commerce/product/**`
- `src/main/java/com/commerce/auth/filter/JwtAuthenticationFilter.java`
- `src/test/java/com/commerce/product/**`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.product.service.ProductServiceTest' --tests 'com.commerce.product.controller.ProductControllerTest'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - architecture.md 디렉토리 구조를 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - 상위 작업 규칙을 위반하지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 목록 응답에 재고 정보를 넣지 마라. 이유: 상세 전용 정보로 분리하기로 결정했다.
- 조회 API에 인증 의존성을 추가하지 마라. 이유: 공개 API로 합의했다.
- 기존 테스트를 깨뜨리지 마라
