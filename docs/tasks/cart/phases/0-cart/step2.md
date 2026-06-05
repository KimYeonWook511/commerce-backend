# Step 2: cart-add-and-list-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cart/prd.md`
- `/docs/tasks/cart/architecture.md`
- `/docs/tasks/cart/adr.md`
- `/docs/tasks/cart/api-spec.md`
- `/docs/tasks/cart/db-schema.md`
- `/docs/architecture.md` (Application 계층 로깅 정책)
- `/docs/logging-conventions.md`
- `/docs/adr.md` (ADR-011)
- `/docs/tasks/cart/phases/0-cart/step1.md` (이전 step)
- `/src/main/java/com/commerce/cart/domain/CartItem.java` (Step 1 산출물)
- `/src/main/java/com/commerce/cart/domain/repository/CartItemRepository.java` (Step 1)
- `/src/main/java/com/commerce/cart/exception/CartErrorCode.java` (Step 1)
- `/src/main/java/com/commerce/order/application/OrderCreateService.java` (Service 패턴)
- `/src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `/src/main/java/com/commerce/order/presentation/OrderController.java` (Controller 패턴)
- `/src/main/java/com/commerce/order/presentation/request/OrderCreateItemRequest.java` (Bean Validation 패턴)
- `/src/main/java/com/commerce/security/annotation/AuthenticatedMemberId.java`
- `/src/main/java/com/commerce/product/domain/Product.java`
- `/src/main/java/com/commerce/product/domain/repository/ProductRepository.java`
- `/src/main/java/com/commerce/common/ApiResponse.java`

## 작업

cart 담기와 조회 API를 추가한다.

### `POST /cart/items` — 담기 (UPSERT)

- 요청 DTO: `src/main/java/com/commerce/cart/presentation/request/CartItemAddRequest.java`
  - `Long productId` — `@NotNull @Positive`
  - `Integer quantity` — `@NotNull @Min(1) @Max(99)`
- Service: `src/main/java/com/commerce/cart/application/AddCartItemService.java`
  - `@Service`, `@Transactional`
  - ADR-011 find-first 패턴: `cartItemRepository.findByMemberIdAndProductId(memberId, productId)`
    - 있으면 `cartItem.increaseQuantity(quantity)` (합산 > 99 시 도메인이 `CART_ITEM_QUANTITY_EXCEEDED` throw)
    - 없으면 `CartItem.create(memberId, productId, quantity)` + `cartItemRepository.save(...)`
  - **UNIQUE race 충돌은 catch하지 말 것** — 안전망 500으로 위임
  - 메서드 반환: `CartItemView`(`productId`, `quantity`)
  - 로그: `log.info("장바구니 항목 추가 memberId={} productId={} quantity={}", memberId, productId, quantity)` — 최종 저장된 quantity 기준
- 응답 DTO: `src/main/java/com/commerce/cart/application/result/CartItemView.java`
  - 필드: `productId`, `quantity` (담기/변경 응답용 단순 view)

### `GET /cart` — 내 cart 조회

- Service: `src/main/java/com/commerce/cart/application/GetMyCartService.java`
  - `@Service`, `@Transactional(readOnly = true)`
  - INFO 로그 없음 (조회 서비스 정책, `docs/architecture.md`)
  - 흐름
    1. `cartItemRepository.findAllByMemberId(memberId)` 호출
    2. 비어 있으면 `CartView(items=[], totalAmount=0)` 반환
    3. `productIds = cartItems.stream().map(CartItem::getProductId).toList()`
    4. `productRepository.findAllById(productIds)` 호출 → `Map<Long, Product>` 구성
    5. CartItem 순서를 유지하면서 `CartItemView`로 변환
       - `name`, `price`, `imageUrl`은 Product에서 채움
       - `lineAmount = product.getPrice() * cartItem.getQuantity()`
       - `unavailable = product.getStatus() == ProductStatus.STOPPED || product.getDeletedAt() != null`
       - **Product가 조회되지 않는 경우**(데이터 정합성 깨짐, 정상 흐름엔 없음): WARN 로그를 남기고 해당 항목은 응답에서 제외한다.
    6. `totalAmount = items.stream().filter(i -> !i.unavailable()).mapToInt(CartItemView::lineAmount).sum()`
- 응답 DTO
  - `src/main/java/com/commerce/cart/application/result/CartItemView.java` — `productId`, `name`, `price`, `imageUrl`, `quantity`, `lineAmount`, `unavailable` 필드 추가 또는 별도 view 분리 검토. **추천**: 동일 record로 nullable 필드를 두기보다는 담기 응답용은 `CartItemAddedView`, 조회 응답용은 `CartItemView`로 분리해 표면을 명확히 한다. 분리 시 step 1에 기재된 result 위치 그대로 이름만 조정.
  - `src/main/java/com/commerce/cart/application/result/CartView.java` — `List<CartItemView> items`, `int totalAmount`

### Controller

- `src/main/java/com/commerce/cart/presentation/CartController.java`
  - `@RestController @RequestMapping("/cart") @RequiredArgsConstructor`
  - `POST /items` → `AddCartItemService.add(memberId, request)` 호출, `201 Created` + `ApiResponse<CartItemAddedView>`
  - `GET ""` → `GetMyCartService.get(memberId)` 호출, `200` + `ApiResponse<CartView>`
  - `@AuthenticatedMemberId Long memberId` 사용
  - 모든 입력은 `@Valid @RequestBody`로 검증. controller에 if 검사 추가 금지.

### 테스트

- `src/test/java/com/commerce/cart/application/AddCartItemServiceTest.java` (단위, Mockito)
  - 신규 insert
  - 기존 항목 합산
  - 합산 > 99 시 도메인 예외 발생
- `src/test/java/com/commerce/cart/application/GetMyCartServiceTest.java` (단위, Mockito)
  - 빈 cart 응답
  - 정상 cart 응답 (price · lineAmount · totalAmount 계산 검증)
  - STOPPED · soft-deleted 항목 unavailable 플래그 + totalAmount 제외
  - Product 누락 시 항목 제외 + WARN 로그
- `src/test/java/com/commerce/cart/presentation/CartControllerTest.java` (`@WebMvcTest` 슬라이스)
  - POST 정상 응답 201
  - quantity=0 요청 시 400 (Bean Validation 거부)
  - productId 음수/null 시 400
  - GET 정상 응답 200

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
   - `@AuthenticatedMemberId`로 memberId를 받고 controller에 if 검사가 없는가?
   - UPSERT는 ADR-011 find-first 패턴을 따르는가? (`DuplicateKeyException` catch 없음)
   - 조회 응답 `totalAmount`가 unavailable 항목을 제외하고 합산하는가?
   - `AddCartItemService`의 INFO 로그가 한국어 본문 + 영어 식별자 + `{}` 패턴인가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- controller에 `if (quantity < 1)` 같은 입력 if 검사를 두지 마라. 이유: 입력 검증은 DTO Bean Validation으로 일원화한다.
- cart 응답에 reason 코드(STOPPED/DELETED 구분)를 노출하지 마라. 이유: boolean 플래그만 노출하기로 결정했다(내부 product 상태 누출 방지).
- `Product`를 `CartItem`이 직접 ManyToOne으로 참조하도록 바꾸지 마라. 이유: ADR-020 ID 참조 정책.
- 조회 endpoint가 cart row를 자동 삭제하는 side effect를 두지 마라. 이유: CQS 위반.
- 기존 테스트를 깨뜨리지 마라.
