# API 스펙

이 문서는 서버가 제공하는 HTTP API의 명세다. 공통 응답 구조·오류 코드와 엔드포인트 작성 형식을 정의하고, 각 엔드포인트는 기능이 추가될 때 기술한다. 필드 타입·검증 같은 상세 스키마는 코드(DTO·Bean Validation)가 단일 출처이며, 이 문서는 계약·의도를 개념 수준으로 기술한다.

## 작성 형식

각 엔드포인트는 아래 형식으로 기술한다. 도메인별로 섹션(`##`)을 두고, 그 아래 엔드포인트(`###`)를 나열한다.

```
## <도메인>

### `<METHOD> /<path>`

설명:
- <이 엔드포인트가 무엇을 하는지>

요청:
- <요청 바디/파라미터>

응답:
- <응답 data 구조, 실패 코드>
```

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
    "member": {
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
    "member": {
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

## 장바구니

### `POST /cart/items`

설명:
- 회원의 장바구니에 상품을 담는 API입니다. 로그인 인증이 필요합니다.
- 같은 회원이 이미 같은 상품을 담아둔 경우 수량이 합산됩니다(UPSERT).
- 합산 결과가 99를 초과하면 4xx로 거부됩니다.
- cart row를 생성·합산하기 전에 Product 존재와 구매 가능 상태를 검증합니다. 미존재 또는 soft-deleted는 `CART-404-2`로, 판매 중지(`STOPPED`)는 `CART-409`로 거부됩니다(결정 6-5).

요청:
- Body

```json
{
  "productId": 123,
  "quantity": 2
}
```

- `productId`: 양수, 필수
- `quantity`: 1 이상 99 이하, 필수

응답:
- HTTP Status: `201 Created`

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 123,
    "quantity": 5
  }
}
```

### `GET /cart`

설명:
- 로그인한 회원의 장바구니를 조회합니다.
- 응답 `items`는 `createdAt DESC` 순서(최근 담은 항목이 위)로 정렬됩니다.
- 각 항목의 가격은 저장된 값이 아니라 최신 `Product` 가격으로 재조회되어 응답됩니다.
- 판매 중지(`STOPPED`)되거나 soft delete된 상품은 `unavailable=true`로 표시되며 `totalAmount` 합산에서 제외됩니다(cart row는 보존, 사용자가 직접 삭제할 수 있도록 유지).
- Product 자체가 누락된 항목(데이터 정합성이 깨진 결함 상황)은 응답에서 제외하고 WARN 로그를 남깁니다. `unavailable` 마킹과는 다른 처리이며 사용자에게 노출되지 않습니다.

요청:
- 요청 바디 없음
- 요청 파라미터 없음

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "items": [
      {
        "productId": 123,
        "name": "상품명",
        "price": 10000,
        "imageUrl": "https://...",
        "quantity": 2,
        "lineAmount": 20000,
        "unavailable": false
      },
      {
        "productId": 456,
        "name": "단종된 상품",
        "price": 5000,
        "imageUrl": null,
        "quantity": 1,
        "lineAmount": 5000,
        "unavailable": true
      }
    ],
    "totalAmount": 20000
  }
}
```

### `PATCH /cart/items/{productId}`

설명:
- 회원 장바구니 항목의 수량을 절대값으로 변경합니다. 로그인 인증이 필요합니다.
- 존재하지 않는 항목에 대한 요청은 `CART-404-1`로 거부됩니다. 잘못된 path 입력(0, 음수 등)도 별도 검증 없이 같은 미존재 정책으로 흡수되어 `CART-404-1`로 응답합니다.

요청:
- Path
  - `productId`: `Long`, 필수 (별도 양수 검증 없음. 코드베이스의 path id는 미존재 항목으로 4xx 응답되는 일관 정책을 따릅니다.)
- Body

```json
{
  "quantity": 5
}
```

- `quantity`: 1 이상 99 이하, 필수

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "productId": 123,
    "quantity": 5
  }
}
```

### `DELETE /cart/items/{productId}`

설명:
- 회원 장바구니에서 단일 항목을 삭제합니다. 로그인 인증이 필요합니다.
- 존재하지 않는 항목에 대한 요청은 `CART-404-1`로 거부됩니다. PATCH와 동일 정책이며, REST DELETE의 멱등 성질을 약하게 깨지만 운영 가시성(silent log miss 방지)과 정책 일관성을 우선합니다.
- 잘못된 path 입력(0, 음수 등)도 별도 검증 없이 같은 미존재 정책으로 흡수되어 `CART-404-1`로 응답합니다.

요청:
- Path
  - `productId`: `Long`, 필수 (별도 양수 검증 없음. 코드베이스의 path id는 미존재 항목으로 4xx 응답되는 일관 정책을 따릅니다.)
- 요청 바디 없음

응답:

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": null
}
```

장바구니 API 실패 응답:
- 존재하지 않는 항목 수량 변경/삭제 (잘못된 path 입력 포함): `CART-404-1`
- 추가 시 상품 미존재(또는 soft-deleted): `CART-404-2`
- 추가 시 상품 판매 중지(STOPPED): `CART-409`
- 수량 invariant 위반(합산 > 99 등): `CART-400-2`
- 잘못된 수량 입력: `CART-400-1`
- 비인증/잘못된 토큰: `AUTH-401`
- 검증 실패: `COMMON-400`
- 동시 추가/수정 race window UNIQUE 충돌: `COMMON-500` 안전망

동시성 처리는 method-level `@Transactional`(→ PR#166) + 본 cart phase ADR 결정 8(낙관적 락 `@Version` + retry + Processor 분리)을 따릅니다. update race는 retry로 흡수되어 정상 응답을 받지만, 새 항목 동시 insert race(같은 사용자가 같은 productId를 cart에 없는 상태에서 ms 단위로 두 번 요청)는 `COMMON-500` 안전망으로 위임됩니다.

주문 생성 흐름의 cart 자동 제거는 별도 API가 아니며, `POST /orders` 성공 시 주문된 productId만 cart에서 자동 제거됩니다.

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

실패 응답:
- `400 INVALID_REQUEST`: `Idempotency-Key` 헤더 누락 또는 빈 값
- `409 ORDER_IDEMPOTENCY_IN_PROGRESS`: 같은 `Idempotency-Key` 로 다른 요청이 처리 중. 클라이언트는 backoff 후 재시도 권장.

### `POST /orders/{orderId}/cancel`

설명:
- 회원이 자신의 주문을 취소합니다. `INIT`(결제 전)과 `PAID`(결제 완료) 주문을 취소할 수 있습니다.
- `INIT` 취소: 재고만 복구합니다(환불 없음).
- `PAID` 취소: 주문을 `CANCELED`로 전이하고 재고를 전량 복구하며 전액 환불합니다. 환불 의도는 취소와 단일 tx로 영속화되고(취소 접수 시점에 보장), 실제 PG 환불은 best-effort로 실행되어 실패·불확실·중단은 CANCEL 대사가 마무리합니다.

요청:
- Path
  - `orderId`: 주문 ID

응답:
- `refundStatus`: 환불 진행 상태. `NONE`(INIT 취소, 환불 없음) / `COMPLETED`(PG 환불 완료) / `IN_PROGRESS`(환불 처리 중 — 대사가 마무리).

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "orderId": 1,
    "status": "CANCELED",
    "refundStatus": "COMPLETED"
  }
}
```

실패:
- 본인 주문이 아니거나 없으면 주문을 찾을 수 없음으로 응답합니다.
- 취소 불가 상태(이미 CANCELED 등)는 거부합니다.
- `PAID`인데 미확정(UNKNOWN/REQUESTED) 승인 결제가 떠 있으면 환불 불가로 거부하며, 환불 대상(SUCCEEDED 승인)이 없는 정합성 오류도 거부합니다(취소를 강행하지 않음).
- PG 환불 실행 실패·불확실은 취소 접수를 무르지 않습니다(`refundStatus=IN_PROGRESS`, 대사가 마무리).

## 결제

### `POST /payments/reserve` (구 `/payments/ready`)

설명:
- 결제창 준비 (예약). 유효한 `PaymentReservation` (`status=RESERVED ∧ expiresAt>now ∧ provider·memberId·amount 일치`) 이 있으면 재사용, 없으면 새 `RESERVED` 행 발급합니다.
- 서버가 merchantPayKey 를 발급하고 `PaymentReservation` 에 저장합니다. 클라이언트 발급 금지.
- **호환 깨는 변경**: URL `POST /payments/ready` → `POST /payments/reserve` (frontend 미개발이라 무방). DTO class 이름만 rename (`PaymentReadyRequest` → `ReservePaymentRequest`, `PaymentReadyResponse` → `ReservePaymentResponse`). 응답 본문 구조 동일.
- UNKNOWN 행 있는 주문 요청은 `PAYMENT_RESULT_PENDING` (409) 으로 차단합니다.

요청:
- Body

```json
{
  "orderId": 1,
  "provider": "NAVERPAY"
}
```

- `orderId`: 주문 ID, 양의 정수, 필수
- `provider`: `PaymentProvider` enum 값, 필수

응답 (200):

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "clientId": "client-id",
    "chainId": "chain-id",
    "merchantPayKey": "PAY-01HXXX...",
    "productName": "대표 상품명",
    "productCount": 2,
    "totalPayAmount": 20000,
    "taxScopeAmount": 20000,
    "taxExScopeAmount": 0,
    "returnUrl": "https://.../return?merchantPayKey=PAY-01HXXX..."
  }
}
```

실패 응답:
- `PAYMENT_RESULT_PENDING` (409): 해당 주문에 UNKNOWN 상태의 Payment 시도가 있어 차단
- `ORDER-404`: 주문 미존재 또는 다른 회원 주문
- `ORDER-409-1`: 결제 불가 주문 상태 (`checkPayable` 실패)

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
- PG redirect 후 승인 처리. `PaymentReservation.merchantPayKey` 기반으로 역조회하여 승인을 진행합니다. Order 를 거치지 않습니다.
- **조회 단일화**: Reservation 은 `(memberId, merchantPayKey)` 로 역조회합니다. 다른 회원의/없는 `merchantPayKey` 는 모두 `PAYMENT_RESERVATION_NOT_FOUND` (404) 가 되어 키 존재 여부를 노출하지 않습니다.
- **멱등 응답**: 같은 `merchantPayKey`·**같은 `pgPaymentId`** 의 redirect 가 중복 도착하고 `status=USED` Reservation 이 발견되면, 차단이 아닌 *기존 결제 결과 200 응답* 으로 흡수합니다. USED 예약에 **다른 `pgPaymentId`** 로 들어오면 이미 소비된 예약 재사용이므로 `PAYMENT_RESERVATION_ALREADY_USED` (409) 로 차단합니다.
- UNKNOWN 행 있는 주문 요청은 `PAYMENT_RESULT_PENDING` (409) 으로 차단합니다.
- 이미 성공(APPROVE·SUCCEEDED)한 결제가 있는 주문에 새 승인이 진입하면 PG 호출 전에 `PAYMENT_DUPLICATE` (409) 로 차단합니다.
- 같은 예약에 다른 `pgPaymentId` 승인이 동시에 들어오면 한쪽만 진행하고 진 쪽은 PG 호출 전에 `PAYMENT_RESERVATION_ALREADY_USED` (409) 로 차단됩니다.

요청:
- Body

```json
{
  "merchantPayKey": "PAY-01HXXX...",
  "pgPaymentId": "naver-pg-id-xxx"
}
```

- `merchantPayKey`: 64 자 이내, 필수
- `pgPaymentId`: 64 자 이내, 필수

응답 (200):

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "pgPaymentId": "naver-pg-id-xxx",
    "status": "SUCCESS"
  }
}
```

멱등 응답 동작:
- USED Reservation 발견 시 기존 `Payment(type=APPROVE, status=SUCCEEDED)` 의 결과를 그대로 반환합니다.
- 차단/에러 응답이 아닙니다. PG redirect 의 *한 번 = 한 번* 정신에 따라 같은 키 중복은 *동일 결과 재반환* 으로 처리합니다 (→ PR#205).

실패 응답:
- `PAYMENT_RESULT_PENDING` (409): 해당 주문에 UNKNOWN 상태의 Payment 시도가 있어 차단
- `PAYMENT_RESERVATION_NOT_FOUND` (404): `(memberId, merchantPayKey)` 로 Reservation 미발견 — 없는 키 또는 다른 회원의 키 (키 존재 비노출)
- `PAYMENT_DUPLICATE` (409): 이미 성공한 결제가 있는 주문에 새 승인 진입 — PG 호출 전 차단
- `PAYMENT_RESERVATION_ALREADY_USED` (409): 같은 예약을 다른 pgPaymentId 로 동시/순차 재사용 — PG 호출 전 차단

### 새 응답 코드 (payment-order-redesign 추가)

| 코드 | HTTP | 의미 |
|---|---|---|
| `PAYMENT_RESULT_PENDING` | 409 | 해당 주문에 UNKNOWN 상태의 Payment 시도가 있어 reserve/approve 차단. 사용자에게 "결제 결과 확인 중" 안내 |
| `PAYMENT_DUPLICATE` | 409 | 이미 성공(APPROVE·SUCCEEDED)한 결제가 있는 주문에 새 승인 진입 — PG 호출 전 진입 가드 차단 또는 `uk_payment_approved_order_key` 위반(최종 보루) |
| `PAYMENT_RESERVATION_ALREADY_USED` | 409 | 같은 예약(merchantPayKey)을 다른 pgPaymentId 로 동시/순차 재사용 — PG 호출 전 차단 |
