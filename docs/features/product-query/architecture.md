# 기능 아키텍처

## 개요

- `product` 도메인에 공개 조회 유스케이스를 추가한다.
- 기존 `Product` 엔티티와 `ProductRepository`를 기반으로 목록과 상세 조회를 제공하고, 상세 조회에서만 `StockRepository`를 통해 재고 수량을 결합한다.

## 변경 대상

- `src/main/java/com/commerce/product`
- `src/test/java/com/commerce/product`
- 루트 문서 중 `docs/api-spec.md`, `docs/architecture.md`

## 설계 방향

- Controller는 공개 GET 엔드포인트를 제공하고 Service 결과를 `ApiResponse`로 감싼다.
- Service는 조회 전용 유스케이스를 담당한다.
- 목록 조회와 상세 조회 DTO를 분리해 목록에는 재고를 포함하지 않는다.
- 목록 정렬은 `Product.createdAt` 기준 최신순으로 고정한다.
- 상세 조회에서 재고 레코드가 없으면 예외 대신 `0`으로 정규화한다.

## 데이터 흐름

- `GET /products`
- `ProductController`가 요청을 수신한다.
- `ProductService`가 `ProductRepository`에서 `createdAt DESC` 목록을 조회한다.
- 조회 결과를 목록 응답 DTO로 매핑해 반환한다.

- `GET /products/{productId}`
- `ProductController`가 요청을 수신한다.
- `ProductService`가 `ProductRepository`에서 상품을 조회한다.
- `StockRepository`에서 `productId` 기준 재고를 조회한다.
- 상품 정보와 재고 수량을 상세 응답 DTO로 조합해 반환한다.

## 예외 및 실패 처리

- 상세 조회 대상 상품이 없으면 `ProductException(ProductErrorCode.PRODUCT_NOT_FOUND)`를 던진다.
- 상세 조회에서 재고 레코드가 없으면 `stockQuantity=0`으로 응답한다.
- 목록 조회는 빈 목록일 수 있으며 이 경우 성공 응답과 빈 배열을 반환한다.

## 테스트 포인트

- 목록 조회가 `createdAt DESC` 기준으로 반환되는지 검증한다.
- 목록 응답에 재고 정보가 포함되지 않는지 검증한다.
- 상세 조회가 재고 수량을 포함하는지 검증한다.
- 상세 조회에서 없는 상품은 `404`를 반환하는지 검증한다.
- 공개 엔드포인트라 인증 헤더 없이도 호출 가능한지 검증한다.
