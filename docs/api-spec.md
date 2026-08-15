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
- `PAID` 취소: 주문을 `CANCELED`로 전이하고 재고를 전량 복구하며 전액 환불합니다. 환불 의도는 취소와 단일 tx로 영속화되고(취소 접수 시점에 보장), 실제 PG 환불은 best-effort로 실행되어 실패·불확실·중단은 환불 대사가 마무리합니다.
- 중복 요청 방지를 위해 `Idempotency-Key` 헤더가 필요합니다.

요청:
- Path
  - `orderId`: 주문 ID
- Header
  - `Idempotency-Key`: 필수, 64자 이하
- 요청 바디 없음

클라이언트는 취소 요청마다 고유값을 만들고, **재시도할 때는 같은 값을 다시 보냅니다.** 값이 바뀌면 새 요청으로 처리되어 중복 방지가 무의미해집니다. 같은 값이 겹치면 안 되는 범위는 그 결제에서 회원이 요청한 환불들이며(주문 생성 키는 그 회원의 주문들), 취소 쪽 범위가 더 좁으므로 주문 생성용 키 생성기를 그대로 써도 됩니다.

응답:
- `refundStatus`: 환불 진행 상태. `NONE` / `COMPLETED` / `IN_PROGRESS` 셋뿐이며, 내부 환불 상태 다섯이 아래처럼 접힙니다.

  | 내부 환불 상태 | `refundStatus` |
  | --- | --- |
  | (환불 없음 — 결제 전 취소) | `NONE` |
  | `SUCCEEDED` | `COMPLETED` |
  | `REQUESTED` · `IN_PROGRESS` · `UNKNOWN` · `MANUAL_REVIEW` | `IN_PROGRESS` |

- `refundedAmount`: 이번 요청으로 환불되는 금액. 주문 총액도, 그 결제의 누적 환불액도 아닙니다.
- `remainingAmount`: 앞으로 더 취소할 수 있는 금액(`승인 금액 − 누적 환불액`). 결과를 모르는 환불도 한도를 이미 잡고 있어 여기서 빠지므로, "아직 안 돌아온 돈"이 아니라 취소 가능 금액입니다. 결제 전 취소는 승인 금액이 없어 0입니다.

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "orderId": 1,
    "status": "CANCELED",
    "refundStatus": "COMPLETED",
    "refundedAmount": 10000,
    "remainingAmount": 0
  }
}
```

같은 `Idempotency-Key`로 다시 요청하면 새 환불을 만들지 않고 앞서 만든 환불의 결과를 그대로 응답합니다(실패가 아닙니다). 결제사를 다시 부르지도 않습니다.

실패:
- 본인 주문이 아니거나 없으면 주문을 찾을 수 없음으로 응답합니다.
- 취소 불가 상태(이미 CANCELED 등)는 거부합니다.
- `PAID`인데 승인 결과를 모르는 결제가 걸려 있으면 환불 불가로 거부하며, 환불 대상(성공한 승인)이 없는 정합성 오류도 거부합니다(취소를 강행하지 않음).
- `400 INVALID_REQUEST`: `Idempotency-Key` 헤더 누락·빈 값 또는 64자 초과
- `400 REFUND_IDEMPOTENCY_KEY_CONFLICT`: 같은 `Idempotency-Key`에 내용이 다른 환불 요청이 왔습니다. 앞 환불을 그대로 돌려주면 요청한 환불이 실행되지 않았는데 성공 응답이 나가므로 거부합니다.
- `409 ORDER_CANCEL_IN_PROGRESS`: 같은 `Idempotency-Key`로 다른 요청이 처리 중. 클라이언트는 backoff 후 재시도 권장.
- PG 환불 실행 실패·불확실은 취소 접수를 무르지 않습니다(`refundStatus=IN_PROGRESS`, 대사가 마무리).

## 결제

### `POST /payments`

설명:
- 결제를 시작합니다. 결제 행을 하나 만들고 결제창을 여는 데 필요한 값을 돌려줍니다. 이 행이 그 주문의 활성 결제 자리를 잡아 이중결제를 막습니다.
- 서버가 결제 키(`merchantPayKey`)를 발급합니다. 클라이언트 발급 금지.
- 요청한 회원의 주문만 결제할 수 있습니다. 남의 주문 번호를 실으면 주문을 찾을 수 없음으로 거부하며, 그 주문이 있는지조차 드러내지 않습니다.
- 그 주문에 앞 결제가 살아 있어도 아직 승인을 부르지 않았다면 그 결제를 종결하고 새 결제가 자리를 이어받습니다. 승인을 부른 뒤라면 결과가 정해질 때까지 새 결제를 막습니다.
- 중복 요청 방지를 위해 `Idempotency-Key` 헤더가 필요합니다.

요청:
- Header
  - `Idempotency-Key`: 필수, 64자 이하
- Body

```json
{
  "orderId": 1,
  "provider": "NAVERPAY"
}
```

- `orderId`: 주문 ID, 양의 정수, 필수
- `provider`: `PaymentPg` enum 값, 필수

클라이언트는 결제 시작 요청마다 고유값을 만들고, **재시도할 때는 같은 값을 다시 보냅니다** — 그래야 결제창이 한 번만 열립니다. 같은 값이 겹치면 안 되는 범위는 그 회원의 결제들입니다. **값을 화면·세션 단위로 붙들어 두면 안 됩니다** — 다른 주문의 결제를 시작할 때 그 값이 다시 나가면 서버가 거부합니다. 값을 새로 만드는 단위는 "이 주문을 결제하겠다"는 한 번의 의사이고, 재시도는 그 안에서 같은 값을 다시 쓰는 것입니다.

같은 `Idempotency-Key`로 다시 요청하면 새 결제를 만들지 않고 앞서 만든 결제의 결제창 정보를 그대로 응답합니다. 서버 쪽 방어가 겨냥하는 것은 끊긴 연결 뒤의 재전송이며, 버튼 연타는 클라이언트가 버튼을 막아 해결합니다.

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
- `400 INVALID_REQUEST`: `Idempotency-Key` 헤더 누락·빈 값 또는 64자 초과
- `400 PAYMENT_IDEMPOTENCY_KEY_CONFLICT`: 같은 `Idempotency-Key`에 다른 주문·다른 금액이 실려 왔습니다. 앞 결제를 그대로 돌려주면 회원이 그 결제창에서 인증해 엉뚱한 주문이 결제되므로 거부합니다.
- `400 PAYMENT_PG_NOT_SUPPORTED`: 지원하지 않는 `provider`
- `409 PAYMENT_REQUEST_IN_PROGRESS`: 같은 요청이 처리 중. 같은 `Idempotency-Key`가 동시에 닿았거나, 같은 주문에 결제 시작이 동시에 와 활성 결제 자리를 다른 요청이 먼저 잡은 경우입니다. 클라이언트는 backoff 후 재시도 권장.
- `409 PAYMENT_RESULT_PENDING`: 그 주문에 승인 결과가 정해지지 않은 결제가 있어 차단
- `409 PAYMENT_DUPLICATE`: 이미 성공한 결제가 있는 주문
- `ORDER-404`: 주문 미존재 또는 다른 회원 주문
- 결제 불가 주문 상태(이미 결제 완료·취소된 주문 등)는 거부합니다.

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

응답:
- 현재 별도 응답 바디 없음

### `POST /payments/naverpay/approve`

설명:
- PG redirect 후 승인 처리. 결제 키(`merchantPayKey`)로 결제를 역조회하여 승인을 진행합니다. Order 를 거치지 않습니다.
- **조회 단일화**: 결제는 `(memberId, merchantPayKey)` 로 역조회합니다. 다른 회원의/없는 키는 모두 `PAYMENT_NOT_FOUND` (404) 가 되어 키 존재 여부를 노출하지 않습니다.
- **멱등 응답**: 이미 승인이 성공한 결제로 redirect 가 중복 도착하면 차단이 아닌 *기존 승인 결과 200 응답* 으로 흡수합니다.
- 승인을 부른 뒤 결과가 정해지지 않은 결제는 `PAYMENT_RESULT_PENDING` (409) 으로 차단합니다. 승인을 호출하고 응답을 기다리는 중인 경우와 응답을 못 받아 결과 불명으로 남은 경우를 가르지 않습니다 — 회원이 할 일이 "결과를 기다리기"로 같고, 내부 상태를 응답으로 드러내지 않습니다.
- 이미 종결된 결제 시도(실패·반려·만료)로 승인이 돌아오면 결제사를 부르지 않고 `PAYMENT_ATTEMPT_CLOSED` (409) 로 차단합니다. 우리가 종결해도 결제사 쪽 예약은 살아 있어 옛 결제창의 인증이 돌아올 수 있습니다.

요청:
- Body

```json
{
  "merchantPayKey": "PAY-01HXXX...",
  "paymentId": "naver-pg-id-xxx"
}
```

- `merchantPayKey`: blank 불가, 필수 — 서버가 발급한 결제 키
- `paymentId`: blank 불가, 필수 — 결제사가 발급한 결제 번호

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

- 승인이 확정됐을 때만 본문이 나갑니다. 확정하지 못한 경우는 전부 실패 응답입니다 — 결과를 모른 채 성공도 실패도 아닌 값을 본문에 담으면 회원이 그것을 결제 완료로 읽습니다.

실패 응답:
- `PAYMENT_NOT_FOUND` (404): `(memberId, merchantPayKey)` 로 결제 미발견 — 없는 키 또는 다른 회원의 키 (키 존재 비노출)
- `PAYMENT_RESULT_PENDING` (409): 승인 결과가 정해지지 않음
- `PAYMENT_ATTEMPT_CLOSED` (409): 종결된 결제 시도로 승인이 돌아옴
- `PAYMENT_APPROVAL_FAILED` (409): 결제사가 승인을 거절
- `PAYMENT_AMOUNT_MISMATCH` (409): 승인 금액이 주문 금액과 다름
- `PAYMENT_ALREADY_CANCELED` (409): 승인 뒤 이미 취소된 결제
- `PAYMENT_KEY_MISMATCH` (409): 승인 응답에 실려 온 결제 키가 이 결제의 것이 아님
- `PAYMENT_DUPLICATE` (409): 이미 결제가 완료된 주문

### 결제 응답 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `PAYMENT_REQUEST_IN_PROGRESS` | 409 | 같은 요청이 이미 처리 중. 같은 멱등키가 동시에 닿았거나 같은 주문의 활성 결제 자리를 다른 요청이 먼저 잡음. 사용자에게 "잠시 후 다시" 안내 |
| `PAYMENT_RESULT_PENDING` | 409 | 승인을 부른 뒤 결과가 정해지지 않은 결제가 그 주문에 있음. 결제 시작과 승인을 함께 차단하며, 사용자에게 "결제 결과 확인 중" 안내. 결제 대사가 이력을 조회해 확정합니다 |
| `PAYMENT_DUPLICATE` | 409 | 이미 결제가 완료된 주문에 새 결제·승인 진입 |
| `PAYMENT_ATTEMPT_CLOSED` | 409 | 실패·반려·만료로 종결된 결제 시도로 승인이 돌아옴. 결제를 다시 시작해야 합니다 |
| `PAYMENT_APPROVAL_FAILED` | 409 | 결제사가 승인을 거절 |
| `PAYMENT_AMOUNT_MISMATCH` | 409 | 승인 금액이 주문 금액과 다름 — 그 결제는 반려로 종결됩니다 |
| `PAYMENT_ALREADY_CANCELED` | 409 | 승인이 성립했으나 그 뒤 취소된 결제 |
| `PAYMENT_KEY_MISMATCH` | 409 | 승인 응답의 결제 키가 이 결제의 것이 아님 |
| `PAYMENT_NOT_FOUND` | 404 | 결제 미발견 — 없는 키 또는 다른 회원의 키 |
| `PAYMENT_IDEMPOTENCY_KEY_CONFLICT` | 400 | 같은 결제 멱등키로 내용이 다른 요청이 옴 |
| `REFUND_IDEMPOTENCY_KEY_CONFLICT` | 400 | 같은 환불 멱등키로 내용이 다른 요청이 옴 |
| `PAYMENT_PG_NOT_SUPPORTED` | 400 | 지원하지 않는 결제 수단 |
