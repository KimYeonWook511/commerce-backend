# 태스크 아키텍처

## 개요

- 신규 `cart` 도메인을 추가한다. 기존 도메인 패턴(`presentation → application → domain ← infrastructure`)을 그대로 따른다.
- 주문 생성 흐름에 cart 제거 단계를 추가하되, `order` 도메인이 `cart` 도메인을 직접 의존하지 않도록 `order.application.port.CartItemRemover` 인터페이스를 도입한다.
- `cart` 도메인은 다른 aggregate(Member, Product)를 객체로 참조하지 않고 ID(`Long`)로 식별한다. 본 결정은 ADR-020에 명문화한다.

## 변경 대상

- 신규 `cart` 도메인
  - `domain/CartItem.java`, `domain/repository/CartItemRepository.java`
  - `exception/CartException.java`, `exception/CartErrorCode.java`
  - `infrastructure/JpaCartItemRepository.java`, `CartItemRepositoryAdapter.java`, `CartItemRemoverAdapter.java`
  - `application/{AddCartItemService, AddCartItemProcessor, GetMyCartService, UpdateCartItemQuantityService, UpdateCartItemQuantityProcessor, RemoveCartItemService}.java`
  - `application/result/{CartResult, CartItemResult, CartItemSummaryResult}.java`
  - `presentation/CartController.java`, `presentation/request/{CartItemAddRequest, CartItemUpdateRequest}.java`
- `order` 도메인 변경
  - `application/port/CartItemRemover.java` 신설
  - `application/OrderCreateProcessor.java`에서 주문 저장 후 cart 제거 호출 추가
- 테스트 인프라
  - `src/test/java/com/commerce/support/CleanupOrder.java`에 `CART` enum 항목 추가
  - `src/test/java/com/commerce/cart/infrastructure/persistence/support/CartPersistenceTestSupport.java` 신설

## 설계 방향

- **Aggregate**: CartItem-only 단일 entity aggregate. root = `CartItem` 자기 자신.
  - cart 자체에 부착되는 메타데이터(쿠폰 슬롯/메모/상태 등)가 없으므로 Cart aggregate를 별도로 두지 않는다.
  - "사용자의 cart" = `cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId)` 결과 list로 표현한다 (정렬은 결정 6-3).
- **참조 방식**: 다른 aggregate는 `Long` ID로만 식별한다(`memberId`, `productId`). FK ManyToOne 사용하지 않는다. 본 결정은 ADR-020에 정리한다.
- **수량 invariant**: `MIN_QUANTITY=1`, `MAX_QUANTITY=99`. `CartItem` 도메인 메서드가 직접 검증한다.
- **UPSERT 흐름**: ADR-011 find-first 패턴 적용. `findByMemberIdAndProductId` → 있으면 `increaseQuantity` → 없으면 `CartItem.create` + `save`. `(member_id, product_id)` UNIQUE race 충돌은 안전망 500으로 위임한다(Application/Adapter에서 `DuplicateKeyException` catch 금지).
- **가격 동기화**: CartItem은 `productId`와 `quantity`만 저장한다. cart 조회 시점에 `productRepository.findAllById(productIds)`로 최신 Product를 조회해 응답을 조립한다.
- **구매 불가 마킹**: 조회 응답의 각 항목 `unavailable` = `product.status == STOPPED || product.deletedAt != null`. `totalAmount`는 `unavailable=false` 항목만 합산한다. cart row는 자동 삭제하지 않는다.
- **주문-cart 연동**: `order.application.port.CartItemRemover`(인터페이스) + `cart.infrastructure.CartItemRemoverAdapter`(구현체). `OrderCreateProcessor`가 주문 저장 후 같은 트랜잭션 내에서 호출한다. 두 도메인 모두 RDB라 동일 트랜잭션이 자연스럽고, cart 제거 실패 시 주문 롤백이 정합성 측면에서 자연스럽다.
- **멱등 처리**: `OrderCreateService` 멱등 응답 경로(Redis hit 또는 DB hit)는 `OrderCreateProcessor.execute`를 호출하지 않으므로 cart 제거도 첫 요청에서만 자동 실행된다. 별도 가드 불필요.

## 데이터 흐름

- 장바구니 담기
  - `CartController` → `AddCartItemService`(retry outer) → `AddCartItemProcessor`(`@Transactional`) → `ProductRepository.findById` 검증 + `CartItemRepository`(find → `increaseQuantity` 또는 `CartItem.create` → save). 결정 8(낙관적 락 + retry + Processor 분리), 결정 6-5(상품 존재·상태 검증).
- 내 장바구니 조회
  - `CartController` → `GetMyCartService` → `CartItemRepository.findAllByMemberIdOrderByCreatedAtDesc` + `ProductRepository.findAllById` → `CartResult` 조립
- 수량 변경
  - `CartController` → `UpdateCartItemQuantityService`(retry outer) → `UpdateCartItemQuantityProcessor`(`@Transactional`) → `CartItemRepository`(find → `changeQuantity` → save). 결정 8 동일 패턴.
- 항목 삭제
  - `CartController` → `RemoveCartItemService` → `CartItemRepository`(find → `delete(entity)`). 결정 6-4(미존재 4xx, entity 통한 delete로 `@Version` race 처리).
- 주문 생성 시 cart 제거
  - `OrderCreateProcessor` → `CartItemRemover.removeByMemberAndProducts(memberId, productIds)`
  - → `CartItemRemoverAdapter` → `CartItemRepository.deleteByMemberIdAndProductIdIn`

## 예외 및 실패 처리

- 수량 invariant 위반(`<MIN`, `>MAX`)은 `CartException(INVALID_CART_ITEM_QUANTITY)` 또는 `CART_ITEM_QUANTITY_EXCEEDED`로 4xx 응답.
- DTO Bean Validation 실패는 기존 공통 검증 오류 응답을 따른다.
- `CartItemRepository` UNIQUE race 충돌은 ADR-011 안전망(500)으로 위임한다.
- cart 제거 실패는 주문 트랜잭션 전체 롤백으로 이어진다(DELETE는 가벼워 실패 확률 낮음).
- 비인증/잘못된 토큰은 기존 `JwtAuthenticationFilter` 정책으로 401 응답.

## 테스트 포인트

- `CartItem` 도메인 수량 invariant (1~99, 경계값, 초과 거부)
- UPSERT 동작: 신규 insert / 기존 항목 합산 / 합산 결과 MAX 초과 거부
- 조회 응답: 최신 가격 반영, `unavailable` 마킹, `totalAmount` 계산(unavailable 제외)
- 수량 절대값 변경(PATCH): 정상/MIN/MAX 경계
- 항목 삭제(DELETE): 존재/미존재 처리
- 주문 생성 통합: 주문 성공 시 cart에서 주문된 항목만 제거, 미주문 항목 유지, cart에 없는 productId 무시
- 비인증 요청 401
