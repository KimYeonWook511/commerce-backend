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
