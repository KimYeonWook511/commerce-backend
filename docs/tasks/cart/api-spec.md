# 태스크 API 스펙

## 개요

- 본 태스크에서 회원용 cart API 4종을 추가한다. 모든 경로는 `/cart` 하위에 위치하며 로그인 인증을 요구한다.
- `JwtAuthenticationFilter.WHITELIST`(`JwtAuthenticationFilter.java:30`)에 `/cart` 경로를 등록하지 않으므로 자동으로 인증 필요 경로로 분류된다.
- 응답 포맷은 기존 공통 응답 `ApiResponse<T>`를 따른다.

## 엔드포인트

### 1) `POST /cart/items` — 장바구니 담기 (UPSERT)

- **인증**: 필요
- **상태 코드**: `201 Created`
- **요청 body**

```json
{
  "productId": 123,
  "quantity": 2
}
```

- **응답 body**

```json
{
  "data": {
    "productId": 123,
    "quantity": 5
  }
}
```

- **검증 규칙**
  - `productId`: `@NotNull @Positive`
  - `quantity`: `@NotNull @Min(1) @Max(99)`
- **동작**
  - Product 존재·구매 가능 상태 검증 (결정 6-5)
    - `productRepository.findById(productId)` 비어있음 또는 `deletedAt != null` → `CART_ITEM_PRODUCT_NOT_FOUND` 404
    - `status == STOPPED` → `CART_ITEM_PRODUCT_UNAVAILABLE` 409
  - `findByMemberIdAndProductId`로 기존 항목 조회
  - 있으면 `increaseQuantity(요청 quantity)` → 합산 > 99 시 `CART_ITEM_QUANTITY_EXCEEDED` 4xx
  - 없으면 `CartItem.create(...)` + `save`
  - UNIQUE race window 충돌은 안전망 500 위임 (ADR-011)

### 2) `GET /cart` — 내 장바구니 조회

- **인증**: 필요
- **상태 코드**: `200 OK`
- **요청**: 없음
- **응답 body**

```json
{
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

- **동작**
  - `cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId)` + `productRepository.findAllById(productIds)`로 응답 조립 (정렬: 결정 6-3)
  - `unavailable = product.status == STOPPED || product.deletedAt != null` (결정 6-1)
  - Product 자체가 누락된 항목은 응답에서 제외하고 WARN 로그(결정 6-2)
  - `lineAmount = product.price * quantity`
  - `totalAmount` = `unavailable=false` 항목의 `lineAmount` 합

### 3) `PATCH /cart/items/{productId}` — 수량 변경 (절대값)

- **인증**: 필요
- **상태 코드**: `200 OK`
- **경로 변수**: `productId` (Long)
- **요청 body**

```json
{
  "quantity": 5
}
```

- **응답 body**

```json
{
  "data": {
    "productId": 123,
    "quantity": 5
  }
}
```

- **검증 규칙**
  - `quantity`: `@NotNull @Min(1) @Max(99)`
  - path `productId`: 별도 Bean Validation 게이트 없음 (코드베이스 path id 일관 정책). 0/음수도 같은 미존재 분기로 흡수되어 `CART_ITEM_NOT_FOUND` 4xx로 응답.
- **동작**
  - `findByMemberIdAndProductId` → 없으면 `CART_ITEM_NOT_FOUND` 4xx
  - 있으면 `changeQuantity(요청 quantity)`

### 4) `DELETE /cart/items/{productId}` — 항목 삭제

- **인증**: 필요
- **상태 코드**: `200 OK`
- **경로 변수**: `productId` (Long)
- **요청 body**: 없음
- **응답 body**

```json
{
  "data": null
}
```

- **검증 규칙**
  - path `productId`: 별도 Bean Validation 게이트 없음 (코드베이스 path id 일관 정책). 0/음수도 같은 미존재 분기로 흡수되어 `CART_ITEM_NOT_FOUND` 4xx로 응답.
- **동작**
  - `findByMemberIdAndProductId` → 없으면 `CART_ITEM_NOT_FOUND` 4xx (결정 6-4, PATCH와 동일 정책)
  - 있으면 `cartItemRepository.delete(cartItem)` 호출. entity 통한 delete로 `@Version` 체크가 적용되어 동시 DELETE race도 `ObjectOptimisticLockingFailureException`으로 surface된다.

## 비고

- 모든 API는 `JwtAuthenticationFilter`가 인증 토큰을 검증하고, `@AuthenticatedMemberId`로 `memberId`를 주입한다.
- 비인증/잘못된 토큰은 기존 정책에 따라 401 응답.
- `POST /cart/items`와 `PATCH /cart/items/{productId}`의 update race는 결정 8(낙관적 락 `@Version` + retry + Processor 분리)로 흡수된다. 새 항목 동시 insert race window의 UNIQUE 충돌은 `GlobalExceptionHandler`의 `DataAccessException` 안전망(500)으로 위임된다(ADR-011, 결정 8 트레이드오프).
- 주문 생성 흐름의 cart 자동 제거는 별도 API가 아니며, `POST /orders` 성공 시 자동으로 일어난다.
