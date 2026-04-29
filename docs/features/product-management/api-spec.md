# 기능 API 스펙

## 개요

- 관리자 상품 등록, 수정, 삭제 API를 추가한다.
- 기존 공개 상품 조회 API는 노출 조건만 변경한다.
- 모든 응답은 기존 `ApiResponse<T>` 형식을 따른다.

## 엔드포인트

### `POST /admin/products`

- 설명: 관리자 상품 등록
- 인증: `ROLE_ADMIN`

요청:

```json
{
  "name": "product",
  "price": 10000,
  "description": "description",
  "imageUrl": "https://example.com/product.png",
  "status": "ON_SALE"
}
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 1,
    "name": "product",
    "price": 10000,
    "description": "description",
    "imageUrl": "https://example.com/product.png",
    "status": "ON_SALE"
  }
}
```

### `PATCH /admin/products/{productId}`

- 설명: 관리자 상품 수정
- 인증: `ROLE_ADMIN`

요청:

```json
{
  "name": "updated-product",
  "price": 12000,
  "description": "updated description",
  "imageUrl": "https://example.com/updated.png",
  "status": "SOLD_OUT"
}
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 1,
    "name": "updated-product",
    "price": 12000,
    "description": "updated description",
    "imageUrl": "https://example.com/updated.png",
    "status": "SOLD_OUT"
  }
}
```

### `DELETE /admin/products/{productId}`

- 설명: 관리자 상품 soft delete
- 인증: `ROLE_ADMIN`

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 1,
    "deleted": true
  }
}
```

### `GET /products`

- 변경: 삭제되지 않고 `status`가 `ON_SALE` 또는 `SOLD_OUT`인 상품만 반환한다.

### `GET /products/{productId}`

- 변경: 삭제되었거나 `status`가 `STOPPED`인 상품은 `PRODUCT-404`로 응답한다.

## 검증 규칙

- `name`: blank 불가
- `price`: 0보다 커야 함
- `status`: 필수, `ON_SALE`, `SOLD_OUT`, `STOPPED` 중 하나
- `description`: 선택
- `imageUrl`: 선택
- `productId`: 양수

## 비고

- 상품 등록 시 재고 레코드는 생성하지 않는다.
- 파일 업로드는 지원하지 않는다.
- 삭제된 상품은 관리자 수정/삭제 대상에서도 찾을 수 없는 상품으로 처리한다.
