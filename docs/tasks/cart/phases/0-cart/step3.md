# Step 3: cart-update-and-delete-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cart/prd.md`
- `/docs/tasks/cart/architecture.md`
- `/docs/tasks/cart/api-spec.md`
- `/docs/architecture.md`
- `/docs/tasks/cart/phases/0-cart/step1.md`
- `/docs/tasks/cart/phases/0-cart/step2.md`
- `/src/main/java/com/commerce/cart/domain/CartItem.java`
- `/src/main/java/com/commerce/cart/domain/repository/CartItemRepository.java`
- `/src/main/java/com/commerce/cart/application/AddCartItemService.java`
- `/src/main/java/com/commerce/cart/presentation/CartController.java`
- `/src/main/java/com/commerce/cart/exception/CartErrorCode.java`

## 작업

cart 수량 변경과 항목 삭제 API를 추가한다.

### `PATCH /cart/items/{productId}` — 수량 절대값 변경

- 요청 DTO: `src/main/java/com/commerce/cart/presentation/request/CartItemUpdateRequest.java`
  - `Integer quantity` — `@NotNull @Min(1) @Max(99)`
- Service: `src/main/java/com/commerce/cart/application/UpdateCartItemQuantityService.java`
  - `@Service @Transactional @RequiredArgsConstructor`
  - 흐름
    1. `cartItemRepository.findByMemberIdAndProductId(memberId, productId)` — 없으면 `CartException(CART_ITEM_NOT_FOUND)`
    2. `cartItem.changeQuantity(request.getQuantity())` (도메인이 invariant 검증)
  - 반환: `CartItemAddedView`(`productId`, `quantity`) 또는 Step 2와 동일한 응답 view
  - 로그: `log.info("장바구니 수량 변경 memberId={} productId={} quantity={}", memberId, productId, quantity)`

### `DELETE /cart/items/{productId}` — 항목 삭제

- Service: `src/main/java/com/commerce/cart/application/RemoveCartItemService.java`
  - `@Service @Transactional @RequiredArgsConstructor`
  - 흐름: `cartItemRepository.deleteByMemberIdAndProductId(memberId, productId)`
    - 존재하지 않아도 멱등 성공
  - 반환: `void`
  - 로그: `log.info("장바구니 항목 삭제 memberId={} productId={}", memberId, productId)`

### Controller 갱신

- `CartController`에 두 endpoint 추가
  - `@PatchMapping("/items/{productId}")` → `200` + `ApiResponse<CartItemAddedView>`
  - `@DeleteMapping("/items/{productId}")` → `200` + `ApiResponse<Void>` (`ApiResponse.of(null)`)
  - 모든 입력은 `@Valid @RequestBody` 또는 path variable 그대로 사용
  - `@AuthenticatedMemberId Long memberId` 사용

### 테스트

- `src/test/java/com/commerce/cart/application/UpdateCartItemQuantityServiceTest.java` (단위)
  - 정상 변경
  - 존재하지 않는 항목 → `CART_ITEM_NOT_FOUND`
  - 도메인 invariant 위반(이론적으론 DTO에서 막히지만 service 직접 호출 가드 확인)
- `src/test/java/com/commerce/cart/application/RemoveCartItemServiceTest.java` (단위)
  - 정상 삭제
  - 존재하지 않을 때도 정상 응답 (멱등)
- `src/test/java/com/commerce/cart/presentation/CartControllerTest.java` 확장
  - PATCH 정상 200
  - PATCH quantity=0 → 400 (Bean Validation)
  - PATCH quantity=100 → 400 (Bean Validation)
  - DELETE 정상 200
  - DELETE 존재 안 함도 200

## 수정 가능 경로

- `src/main/java/com/commerce/cart/**`
- `src/test/java/com/commerce/cart/**`
- `docs/tasks/cart/**`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.cart.*'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - PATCH는 절대값 변경이고 도메인 메서드 `changeQuantity`를 사용하는가?
   - DELETE는 멱등하게 동작하는가(존재 안 해도 200)?
   - DTO Bean Validation으로 quantity 경계 검증이 일원화돼 있는가?
   - `RemoveCartItemService`, `UpdateCartItemQuantityService`에 INFO 로그가 있는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- PATCH 수량을 `increaseQuantity`(상대값)로 처리하지 마라. 이유: api-spec.md가 절대값 변경으로 정의했다.
- DELETE에서 존재하지 않을 때 404를 던지지 마라. 이유: 멱등 동작이 사용자에게 더 자연스럽다(이미 삭제된 상태와 구분 불필요).
- controller에 if-검사를 추가하지 마라. 이유: DTO Bean Validation으로 일원화한다.
- 기존 테스트를 깨뜨리지 마라.
