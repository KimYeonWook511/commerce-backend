# 기능 API 스펙

## 개요

- 관리자 재고 등록, 증가, 감소, 이력 조회 API를 추가한다.
- 모든 응답은 기존 `ApiResponse<T>` 형식을 따른다.

## 엔드포인트

### `POST /admin/products/{productId}/stock`

- 설명: 관리자 초기 재고 생성
- 인증: `ROLE_ADMIN`

요청:

```json
{
  "quantity": 10,
  "reason": "INBOUND"
}
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 1,
    "stockId": 1,
    "quantity": 10
  }
}
```

### `POST /admin/products/{productId}/stock/increase`

- 설명: 관리자 재고 증가
- 인증: `ROLE_ADMIN`

요청:

```json
{
  "quantity": 5,
  "reason": "INBOUND"
}
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 1,
    "stockId": 1,
    "quantity": 15
  }
}
```

### `POST /admin/products/{productId}/stock/decrease`

- 설명: 관리자 재고 감소
- 인증: `ROLE_ADMIN`

요청:

```json
{
  "quantity": 3,
  "reason": "DISPOSAL"
}
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 1,
    "stockId": 1,
    "quantity": 12
  }
}
```

### `GET /admin/products/{productId}/stock/histories`

- 설명: 관리자 상품별 재고 이력 조회
- 인증: `ROLE_ADMIN`
- 정렬: `createdAt DESC`

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": [
    {
      "historyId": 2,
      "productId": 1,
      "stockId": 1,
      "quantityChange": -3,
      "reason": "DISPOSAL",
      "adminMemberId": 10,
      "createdAt": "2026-04-30T12:30:00"
    },
    {
      "historyId": 1,
      "productId": 1,
      "stockId": 1,
      "quantityChange": 10,
      "reason": "INBOUND",
      "adminMemberId": 10,
      "createdAt": "2026-04-30T12:00:00"
    }
  ]
}
```

## 검증 규칙

- `productId`: 양수
- 초기 재고 `quantity`: 0 이상
- 증가/감소 `quantity`: 0보다 커야 함
- `reason`: 필수, `INBOUND`, `DISPOSAL`, `ADMIN_ADJUSTMENT`, `ORDER_CANCEL_RESTORE` 중 하나

## 실패 응답

- 상품 없음: 기존 `PRODUCT-404`
- 재고 없음: 기존 `STOCK-404`
- 재고 부족: 기존 `STOCK-409`
- 이미 재고 존재: 신규 stock 예외 코드
- 권한 없음: 기존 `AUTH-403`
- 검증 실패: 기존 `COMMON-400`

## 비고

- 초기 재고 생성은 기존 상품 등록 API에 포함하지 않는다.
- 재고 이력의 변경 주체는 요청 바디가 아니라 인증된 관리자 member id로 기록한다.
- 이력 조회는 첫 버전에서 pagination을 제공하지 않는다.
