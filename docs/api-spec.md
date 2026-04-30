# API 스펙

## 공통 응답 구조

모든 정상 응답과 오류 응답은 `ApiResponse<T>` 형태를 사용합니다.

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {}
}
```

- `code`: 처리 결과 코드
- `message`: 처리 결과 메시지
- `data`: 실제 응답 데이터

정상 응답 예시:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "orderId": 1,
    "status": "INIT"
  }
}
```

오류 응답 예시:

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청입니다.",
  "data": null
}
```

검증 오류 응답 예시:

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청입니다.",
  "data": {
    "email": "must not be blank",
    "password": "size must be between 8 and 20"
  }
}
```

## 인증

### `POST /auth/signup`

설명:
- 회원 가입을 처리하고, 회원 정보와 함께 access token, refresh token을 발급합니다.
- 응답 본문 외에도 `Authorization` 헤더와 `refreshToken` 쿠키를 함께 반환합니다.

요청:
- Body

```json
{
  "email": "user@example.com",
  "password": "password123",
  "username": "tester"
}
```

- `email`: 이메일 형식, 필수
- `password`: 8자 이상 20자 이하, 필수
- `username`: 12자 이하, 필수

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "memberDetailResult": {
      "memberId": 1,
      "email": "user@example.com",
      "username": "tester"
    },
    "accessToken": "jwt-access-token",
    "refreshToken": "jwt-refresh-token"
  }
}
```

### `POST /auth/login`

설명:
- 이메일과 비밀번호로 로그인하고, 회원 정보와 함께 access token, refresh token을 발급합니다.
- 응답 본문 외에도 `Authorization` 헤더와 `refreshToken` 쿠키를 함께 반환합니다.

요청:
- Body

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

- `email`: 이메일 형식, 필수
- `password`: 필수

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "memberDetailResult": {
      "memberId": 1,
      "email": "user@example.com",
      "username": "tester"
    },
    "accessToken": "jwt-access-token",
    "refreshToken": "jwt-refresh-token"
  }
}
```

### `POST /auth/reissue`

설명:
- refresh token으로 access token과 refresh token을 재발급합니다.
- `Cookie.refreshToken`을 우선 사용하고, 쿠키가 없으면 body의 `refreshToken`을 사용합니다.
- 응답 본문 외에도 `Authorization` 헤더와 `refreshToken` 쿠키를 함께 반환합니다.

요청:
- Cookie
  - `refreshToken`
- 또는 Body

```json
{
  "refreshToken": "jwt-refresh-token"
}
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "accessToken": "new-access-token",
    "refreshToken": "new-refresh-token"
  }
}
```

## 상품

### `GET /products`

설명:
- 비로그인 사용자도 호출 가능한 공개 상품 목록 조회 API입니다.
- 삭제되지 않고 `status`가 `ON_SALE` 또는 `SOLD_OUT`인 상품만 조회합니다.
- 상품은 `createdAt DESC` 기준 최신 등록순으로 조회합니다.
- 목록 응답에는 재고 정보를 포함하지 않습니다.

요청:
- 요청 바디 없음
- 요청 파라미터 없음
- 인증 헤더 없음

응답:

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

### `GET /products/{productId}`

설명:
- 비로그인 사용자도 호출 가능한 공개 상품 상세 조회 API입니다.
- 삭제되지 않고 `status`가 `ON_SALE` 또는 `SOLD_OUT`인 상품만 조회할 수 있습니다.
- 상품 기본 정보와 현재 재고 수량을 함께 반환합니다.
- 재고 레코드가 없으면 `stockQuantity`는 `0`으로 응답합니다.

요청:
- Path
  - `productId`: 양수, 필수
- 요청 바디 없음
- 인증 헤더 없음

응답:

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

존재하지 않거나 공개 대상이 아닌 상품 상세 조회 응답:

```json
{
  "code": "PRODUCT-404",
  "message": "상품을 찾을 수 없습니다",
  "data": null
}
```

## 관리자 상품

### `POST /admin/products`

설명:
- 관리자 상품 등록 API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 상품 등록 시 재고 레코드는 생성하지 않습니다.

요청:
- Body

```json
{
  "name": "product",
  "price": 10000,
  "description": "description",
  "imageUrl": "https://example.com/product.png",
  "status": "ON_SALE"
}
```

- `name`: blank 불가, 필수
- `price`: 0보다 커야 함, 필수
- `description`: 선택
- `imageUrl`: 선택
- `status`: `ON_SALE`, `SOLD_OUT`, `STOPPED` 중 하나, 필수

응답:
- HTTP Status: `201 Created`

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

설명:
- 관리자 상품 수정 API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 삭제된 상품은 수정할 수 없습니다.

요청:
- Path
  - `productId`: 양수, 필수
- Body

```json
{
  "name": "updated-product",
  "price": 12000,
  "description": "updated description",
  "imageUrl": "https://example.com/updated.png",
  "status": "SOLD_OUT"
}
```

- `name`: blank 불가, 필수
- `price`: 0보다 커야 함, 필수
- `description`: 선택
- `imageUrl`: 선택
- `status`: `ON_SALE`, `SOLD_OUT`, `STOPPED` 중 하나, 필수

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

설명:
- 관리자 상품 soft delete API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 삭제된 상품은 공개 조회와 관리자 수정/삭제 대상에서 제외됩니다.

요청:
- Path
  - `productId`: 양수, 필수
- 요청 바디 없음

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

## 관리자 재고

### `POST /admin/products/{productId}/stock`

설명:
- 관리자 초기 재고 생성 API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 초기 재고 생성은 삭제되지 않은 상품에 대해 상품별 한 번만 가능합니다.

요청:
- Path
  - `productId`: 양수, 필수
- Body

```json
{
  "quantity": 10,
  "reason": "INBOUND"
}
```

- `quantity`: 0 이상, 필수
- `reason`: `INBOUND`, `DISPOSAL`, `ADMIN_ADJUSTMENT`, `ORDER_CANCEL_RESTORE` 중 하나, 필수

응답:
- HTTP Status: `201 Created`

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

설명:
- 관리자 재고 증가 API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 비관적 락으로 재고를 조회한 뒤 수량을 증가시키고 양수 변경 이력을 저장합니다.

요청:
- Path
  - `productId`: 양수, 필수
- Body

```json
{
  "quantity": 5,
  "reason": "INBOUND"
}
```

- `quantity`: 0보다 커야 함, 필수
- `reason`: `INBOUND`, `DISPOSAL`, `ADMIN_ADJUSTMENT`, `ORDER_CANCEL_RESTORE` 중 하나, 필수

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

설명:
- 관리자 재고 감소 API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 비관적 락으로 재고를 조회한 뒤 수량을 감소시키고 음수 변경 이력을 저장합니다.
- 현재 재고 수량보다 크게 감소할 수 없습니다.

요청:
- Path
  - `productId`: 양수, 필수
- Body

```json
{
  "quantity": 3,
  "reason": "DISPOSAL"
}
```

- `quantity`: 0보다 커야 함, 필수
- `reason`: `INBOUND`, `DISPOSAL`, `ADMIN_ADJUSTMENT`, `ORDER_CANCEL_RESTORE` 중 하나, 필수

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

설명:
- 관리자 상품별 재고 변경 이력 조회 API입니다.
- `ROLE_ADMIN` 권한이 필요합니다.
- 상품별 전체 이력을 `createdAt DESC` 기준 최신순으로 반환합니다.

요청:
- Path
  - `productId`: 양수, 필수
- 요청 바디 없음
- pagination 파라미터 없음

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

관리자 재고 API 실패 응답:
- 존재하지 않거나 삭제된 상품의 초기 재고 생성: `PRODUCT-404`
- 존재하지 않는 재고의 증가/감소/이력 조회: `STOCK-404`
- 재고 부족: `STOCK-409`
- 이미 재고 존재: `STOCK-409-2`
- 권한 없음: `AUTH-403`
- 검증 실패: `COMMON-400`

## 주문

### `POST /orders`

설명:
- 회원의 주문을 생성합니다.
- 중복 요청 방지를 위해 `Idempotency-Key` 헤더가 필요합니다.

요청:
- Header
  - `Idempotency-Key`: 필수
- Body

```json
{
  "items": [
    {
      "productId": 1,
      "quantity": 2
    }
  ]
}
```

- `items`: 1개 이상 필요
- `productId`: 양수, 필수
- `quantity`: 양수, 필수

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "orderId": 1,
    "totalPrice": 20000,
    "status": "INIT"
  }
}
```

### `POST /orders/{orderId}/cancel`

설명:
- 회원이 자신의 주문을 취소합니다.

요청:
- Path
  - `orderId`: 주문 ID

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "orderId": 1,
    "status": "CANCELED"
  }
}
```

## 결제

### `POST /payments/ready`

설명:
- 결제 승인 전에 필요한 결제 준비 정보를 생성합니다.
- 현재 provider 문자열은 내부적으로 `PaymentProvider` enum으로 변환됩니다.

요청:
- Body

```json
{
  "orderId": 1,
  "provider": "NAVERPAY"
}
```

- `orderId`: 주문 ID, 필수
- `provider`: 결제 수단, 필수

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "clientId": "client-id",
    "chainId": "chain-id",
    "merchantPayKey": "merchant-pay-key",
    "productName": "대표 상품명",
    "productCount": 2,
    "totalPayAmount": 20000,
    "taxScopeAmount": 20000,
    "taxExScopeAmount": 0,
    "returnUrl": "http://localhost:8080/payments/naverpay/return"
  }
}
```

### `GET /payments/naverpay/return`

설명:
- 네이버페이 결제 완료 후 리다이렉트되는 엔드포인트입니다.
- 현재는 리턴 파라미터를 수신하는 용도로만 존재하며 별도 응답 바디를 반환하지 않습니다.

요청:
- Query Parameter
  - `merchantPayKey`: 필수
  - `resultCode`: 필수
  - `resultMessage`: 선택
  - `paymentId`: 선택
  - `reserveId`: 선택

응답:
- 현재 별도 응답 바디 없음

### `POST /payments/naverpay/approve`

설명:
- 네이버페이 승인 결과를 반영하고 결제를 완료 처리합니다.

요청:
- Body

```json
{
  "merchantPayKey": "merchant-pay-key",
  "paymentId": "naver-pay-payment-id"
}
```

- `merchantPayKey`: 필수
- `paymentId`: 필수

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "pgPaymentId": "naver-pay-payment-id",
    "status": "SUCCEEDED"
  }
}
```
