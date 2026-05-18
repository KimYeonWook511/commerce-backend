# 기능 API 스펙

## 개요

- 공개 상품 목록 조회 API와 공개 상품 상세 조회 API를 추가한다.

## 엔드포인트

- 메서드
- `GET`
- 경로
- `/products`
- 설명
- 최신 등록순으로 전체 상품 목록을 조회한다.

- 메서드
- `GET`
- 경로
- `/products/{productId}`
- 설명
- 특정 상품의 상세 정보와 현재 재고 수량을 조회한다.

## 요청

- `GET /products`
- 요청 바디 없음
- 요청 파라미터 없음
- 인증 헤더 없음

- `GET /products/{productId}`
- Path Variable
- `productId`: 양수, 필수
- 요청 바디 없음
- 인증 헤더 없음

## 응답

- `GET /products`

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": [
    {
      "productId": 2,
      "name": "latest-product",
      "price": 3000
    },
    {
      "productId": 1,
      "name": "old-product",
      "price": 1000
    }
  ]
}
```

- `GET /products/{productId}`

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 2,
    "name": "latest-product",
    "price": 3000,
    "stockQuantity": 7
  }
}
```

- 존재하지 않는 상품 상세 조회

```json
{
  "code": "PRODUCT-404",
  "message": "상품을 찾을 수 없습니다",
  "data": null
}
```

## 검증 규칙

- `productId`는 양수여야 한다.
- 목록 조회는 정렬 기준을 외부 입력으로 받지 않고 `createdAt DESC`로 고정한다.
- 상세 조회에서 재고 레코드가 없으면 `stockQuantity=0`으로 응답한다.

## 비고

- 두 엔드포인트 모두 공개 API다.
- 목록 응답에는 재고 정보를 포함하지 않는다.
- 상세 응답에만 정확한 재고 수량을 포함한다.
