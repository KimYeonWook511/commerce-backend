# 태스크 ADR

## 결정 1: CartItem-only 단일 entity aggregate를 사용한다

- **배경**: cart를 도메인으로 모델링할 때 (a) Cart(root) - CartItem(N) 구조 또는 (b) CartItem 단일 entity aggregate 두 가지 선택지가 있었다. PRD 범위에서 cart 자체에 부착되는 메타데이터(쿠폰 슬롯/메모/cart 상태/만료 등)는 없으며, 사용자당 cart는 1개로 결정되었다.
- **결정 내용**: Cart aggregate root를 두지 않고 `CartItem(memberId, productId, quantity)`만 둔다. root = `CartItem` 자신. "사용자의 cart"는 `findAllByMemberId(memberId)` 결과 리스트로 표현한다.
- **근거**: CLAUDE.md "불필요한 추상화와 과한 설계를 피한다" 원칙과 부합한다. Cart entity를 만들어도 `(id, memberId, createdAt, updatedAt)`만 가진 빈 컨테이너가 된다. 우리 코드베이스의 `StockHistory`, `RefreshToken` 같은 단일 entity aggregate와 동일한 패턴이다. 미래에 cart 레벨 메타데이터(셀러별 분리, 위시리스트, 쿠폰 슬롯 등)가 도입되면 Cart aggregate를 추가하고 CartItem에 `cartId` FK를 붙이는 마이그레이션으로 자연 확장할 수 있다.
- **결과**: 단일 테이블 `tbl_cart_item`만 신설된다. "여러 인스턴스 다루기" 책임(주문 후 일괄 제거 등)은 Repository와 application service에 위치한다.

## 결정 2: cart는 다른 aggregate를 ID(`Long`)로만 참조한다 (ADR-020 명문화)

- **배경**: 기존 도메인은 `@ManyToOne` 객체 참조(Order.member, OrderItem.product, Stock.product 등)를 사용한다. 그러나 application 계층은 대부분 `memberId` 등 ID 기반으로 다루고 있어 도메인과 인터페이스 사이에 이중 표현이 발생하고, N+1 회피 및 fetch join 부담, 도메인 결합도 증가, DDD "다른 aggregate는 ID로만 참조" 원칙 위반 같은 단점이 있었다.
- **결정 내용**: 본 phase에서 신규 도메인 `cart`는 `memberId: Long`, `productId: Long`만 저장한다. FK 관계(`@ManyToOne`, `@JoinColumn`)는 사용하지 않는다. 이 정책은 `docs/ADR.md`에 **ADR-020 "신규 도메인의 cross-aggregate 참조는 ID로 한다"**로 명문화한다.
- **근거**: DDD 정통(Eric Evans) "Reference Other Aggregates Only By Identity" 원칙. 다른 aggregate와의 결합도 감소, 단위 테스트 단순, JPA lifecycle 함정 회피, 마이크로서비스 분리 친화적. 기존 Order/Stock 등 ManyToOne 마이그레이션은 별도 트랙으로 분리한다.
- **결과**: cart 조회 시 `productRepository.findAllById(productIds)`로 명시적으로 Product를 조회해 응답을 조립한다. DB 참조 무결성은 application과 unique 제약·삭제 순서 정책으로 책임진다. 본 phase 이후 신규 도메인은 본 정책을 기본값으로 한다.

## 결정 3: 가격은 cart에 저장하지 않고 조회 시점에 Product에서 재조회한다

- **배경**: cart에 담은 시점의 가격을 저장하면 가격 변동 시 사용자에게 노출 또는 차익 운영이 가능하지만, 도메인이 무거워지고 가격 변동 알림/스냅샷 동기화 같은 복잡도가 추가된다.
- **결정 내용**: `CartItem`은 `productId`와 `quantity`만 저장한다. cart 조회 시점에 `Product`를 다시 조회해 최신 `price`, `name`, `imageUrl`, `status`, `deletedAt`을 응답에 조립한다.
- **근거**: TEMP-TODO "가격은 항상 최신 상품 기준으로 재조회" 정책과 부합한다. PRD 결제 흐름이 주문 시점에 Product 가격을 다시 조회·검증하므로 cart에 저장된 가격은 어차피 신뢰할 수 없다. 도메인 단순화와 정합성 일관성을 우선한다.
- **결과**: cart 응답 조립 시 Product 추가 조회 1회가 발생하나 인덱스 기반 PK 조회이므로 영향이 미미하다. 가격 변동 알림은 추후 phase에서 별도로 다룬다.

## 결정 4: 주문 생성 트랜잭션 내에서 cart 항목을 제거한다

- **배경**: 주문 성공 시 cart 제거 시점·트랜잭션 정책으로 (a) 주문 트랜잭션 내 호출, (b) `@TransactionalEventListener(AFTER_COMMIT)` 분리 두 가지를 비교했다.
- **결정 내용**: `OrderCreateProcessor`의 주문 저장 트랜잭션 안에서 `CartItemRemover.removeByMemberAndProducts(memberId, productIds)`를 호출한다. cart 제거 실패 시 주문도 함께 롤백된다.
- **근거**: cart와 order 모두 동일 RDB이므로 ADR-005가 가리키는 "외부 시스템(Redis) 이슬 파"가 아니다. cart `DELETE WHERE`는 매우 가벼워 실패 확률이 낮다. 정합성 측면에서 "주문이 성공했는데 cart가 그대로"인 일시 불일치를 만들지 않는다. AFTER_COMMIT 분리는 동일 트랜잭션의 단순성을 잃는 비용이 더 크다.
- **결과**: cart 제거는 첫 주문 요청 트랜잭션에서만 실행된다. `OrderCreateService`의 멱등 응답 경로는 `OrderCreateProcessor.execute`를 호출하지 않으므로 두 번 제거되지 않는다(별도 가드 불필요).

## 결정 5: 주문 항목 cart 존재 여부 검증은 하지 않는다

- **배경**: TEMP-TODO에 "주문 생성 시 장바구니 기반 검증" 항목이 있어, 주문 요청 productId가 cart에 존재해야 주문을 허용할지 여부를 결정해야 했다.
- **결정 내용**: 주문 요청은 cart와 독립적으로 처리한다. 주문 시점에 `Product` 재조회로 존재·가격을 검증하는 기존 흐름을 유지하고, cart 존재 검증은 하지 않는다. 주문 성공 후 주문된 productId 중 cart에 있는 것만 제거한다(`deleteByMemberIdAndProductIdIn`).
- **근거**: cart 존재 검증을 강제하면 "지금 구매(Buy Now)", 주문 재시도 등 정상 흐름을 의도치 않게 차단한다. cart는 사용자 편의의 임시 보관소이지 주문 권한의 원천이 아니다. cart에 없는 productId가 주문에 포함되어도 `deleteByMemberIdAndProductIdIn`은 0 row 삭제로 자연 처리된다.
- **결과**: 주문-cart 결합도가 최소화된다. 모든 정상 주문 경로(cart 경유/Buy Now/재시도)에서 동일한 흐름이 적용된다.

## 결정 6: cart 응답·엔드포인트 동작 정책

본 결정은 cart 응답과 엔드포인트 동작에 관한 정책을 묶어 정리한다. 6-1은 phase 초기 결정, 6-2~6-5는 PR #166 코드 리뷰 대응 과정에서 명시화·강화되었다.

### 6-1. 구매 불가 상품(STOPPED / soft-deleted)은 응답에 `unavailable=true`로 표시하고 cart row는 보존한다

- **배경**: cart에 담아둔 상품이 판매 중지되거나 soft delete된 경우 (a) cart에서 자동 제거, (b) 응답에서 제외만, (c) `unavailable` 마킹하여 노출 세 가지를 비교했다.
- **결정 내용**: cart 응답의 각 항목에 `unavailable` boolean을 둔다(`status == STOPPED || deletedAt != null`). `totalAmount`는 `unavailable=false` 항목만 합산한다. cart row는 자동 삭제하지 않는다. 사유 코드는 노출하지 않고 boolean 플래그만 제공한다.
- **근거**: 한국 이커머스(올리브영 등)의 일반 UX와 부합한다. 사용자가 "내가 담은 상품이 사라진" 혼란을 겪지 않고 직접 삭제할 수 있다. 조회 endpoint에서 자동 삭제(side effect)는 CQS 위반이라 채택하지 않는다. 사유 코드는 내부 product 상태가 외부로 새는 표면을 줄이기 위해 boolean만 노출한다.
- **결과**: cart 데이터는 보존되고 UI는 boolean 플래그로 분기한다. 사용자가 명시적으로 삭제 호출 시에만 row가 사라진다.

### 6-2. Product 자체가 누락된 항목은 응답에서 제외하고 WARN 로그를 남긴다

- **배경**: 6-1의 `unavailable` 마킹은 정상 운영 상태(`STOPPED`/soft-deleted)에 대한 처리다. `productRepository.findAllById`에서 Product가 아예 조회되지 않는 경우는 데이터 정합성이 깨진 결함 상황으로 별개의 처리가 필요하다. PR #166 코드 리뷰에서 두 케이스의 처리 차이가 의도된 것인지 질문이 있었다.
- **결정 내용**: cart 조회 시 productId로 Product를 조회하지 못한 항목은 응답 `items`에서 제외하고 WARN 로그를 남긴다. 6-1의 마킹 노출과 달리 사용자에게 노출되지 않는다.
- **근거**: STOPPED/soft-deleted는 정상 운영 상태라 사용자가 인지·삭제할 수 있어야 하지만, Product 자체 누락은 발생하면 안 되는 결함이므로 운영 알람이 우선이고 사용자 노출은 부적절하다. 명시 문서화로 두 케이스 처리 차이의 의도를 분명히 한다.

### 6-3. cart 조회는 `createdAt DESC`로 정렬한다

- **배경**: 초기 구현은 `findAllByMemberId`만 호출하여 정렬을 명시하지 않았다. DB 구현에 따라 응답 순서가 달라질 수 있고 Frontend 명세에 영향을 준다는 지적이 PR #166 리뷰에서 있었다.
- **결정 내용**: `cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId)`로 정렬을 명시한다. "최근 담은 항목이 위에" 보이는 일반 이커머스 UX와 부합한다.
- **근거**: `BaseTimeEntity.createdAt`이 항목별로 보관되어 추가 인덱스 없이 정렬 가능하다. cart는 보통 항목 수가 적어 정렬 비용도 무시 가능.

### 6-4. `DELETE /cart/items/{productId}` 미존재 시 `CART_ITEM_NOT_FOUND` 4xx로 응답한다

- **배경**: 초기 구현은 미존재해도 200 + `ApiResponse.of(null)`을 반환하는 멱등 정책이었다. 그러나 `RemoveCartItemService`가 0 row 영향이어도 `log.info("장바구니 항목 삭제 ...")`를 silent로 찍어 운영 로그가 부정확해지는 문제가 있었다. 또한 `PATCH`(`UpdateCartItemQuantityService`)는 미존재 시 `CART_ITEM_NOT_FOUND` throw로 정책 비대칭이 있었다.
- **결정 내용**: `RemoveCartItemService`도 `findByMemberIdAndProductId`로 사전 조회한 뒤 미존재면 `CartException(CART_ITEM_NOT_FOUND)`을 throw한다. PATCH와 동일 정책이며 응답은 4xx.
- **근거**: (a) PATCH와의 정책 일관성, (b) silent log 회귀 자동 해결(throw → log 안 찍힘), (c) cart DELETE는 사용자 명시 액션이라 멱등 시나리오(동일 DELETE 재요청)가 거의 발생하지 않아 멱등 가치보다 명확한 피드백 가치가 더 크다. REST DELETE 정통의 멱등 성질을 약하게 깨지만 도메인 무결성·운영 가시성 우선 정책이다.
- **race 처리**: `findByMemberIdAndProductId`로 managed entity를 로드한 뒤 `cartItemRepository.delete(cartItem)`을 호출한다. entity 통한 delete는 `@Version` 체크가 적용되므로, 동시 DELETE race(다른 트랜잭션이 먼저 같은 항목을 삭제하는 경우)에서는 두 번째 트랜잭션이 `ObjectOptimisticLockingFailureException`을 던지며 `GlobalExceptionHandler.handleOptimisticLockingFailureException`가 이를 적절한 응답으로 매핑한다. bulk `@Modifying` DELETE 쿼리는 persistence context와 `@Version` 체크를 bypass하므로 채택하지 않는다. 따라서 race 시점의 silent log 회귀도 차단된다.

### 6-5. 장바구니 추가 시 Product 존재·구매 가능 상태를 검증한다

- **배경**: PR #166 코드 리뷰(codex P2)에서 `POST /cart/items`가 `productId`의 `@Positive` 조건만 확인하고 실제 상품 존재 여부를 확인하지 않아, 임의의 미존재 `productId`로도 201을 받고 cart row가 생성되는 결함이 지적되었다. cart는 ADR-020에 따라 FK를 두지 않으므로 데이터 정합성은 application 검증으로 보장해야 한다.
- **결정 내용**: `AddCartItemProcessor`가 cart row를 생성·합산하기 전에 `productRepository.findById`로 Product를 조회하고 (a) 미존재 또는 `deletedAt != null` → `CART_ITEM_PRODUCT_NOT_FOUND`(404), (b) `status == STOPPED` → `CART_ITEM_PRODUCT_UNAVAILABLE`(409)로 거부한다.
- **근거**:
  - **데이터 정합성**: ADR-020 ID 참조에서는 DB FK가 없어 application이 검증의 유일한 게이트다.
  - **6-1과의 분리**: 6-1의 `unavailable=true`는 "담은 후 상태가 바뀐 항목"을 보존·표시하는 정책이다. "처음부터 못 사는 상품을 새로 담는 동작"은 별개 문제로 add 시점에 차단하는 것이 자연스럽다.
  - **도메인 일관성**: `OrderCreateProcessor` 흐름도 주문 생성 시점에 Product 존재·상태를 검증하므로 cart도 동일 정책을 적용한다.
  - **비용**: PK 단건 조회 1회로 인덱스 hit, JPA 1차 캐시 이내라 무겁지 않다.
  - **코드 분리**: `NOT_FOUND`/`UNAVAILABLE`을 분리해 운영 관점에서 결함(미존재·삭제됨)과 일시 차단(STOPPED)을 구분 추적할 수 있게 한다.
- **결과**:
  - 미존재 `productId`로 cart row를 생성할 수 없다. orphan row와 GET 시점 WARN 누적이 차단된다.
  - retry 흐름에서 매 attempt마다 `productRepository.findById`가 호출되나 cart contention이 거의 0이라 사실상 1회 호출과 같다.
  - `AddCartItemProcessor`만 Product를 의존한다. PATCH는 기존 cart row의 수량 변경이라 별도 product 검증을 두지 않는다(이미 6-1의 `unavailable` 응답으로 운영 가시성이 확보됨).

## 결정 7: 항목당 고정 수량 상한(MIN=1, MAX=99)을 도메인에서 강제한다

- **배경**: 수량 상한 정책으로 (a) 항목당 고정 상한, (b) 상품 재고까지 허용(이중 검증), (c) 상한 없음을 비교했다.
- **결정 내용**: `CartItem.MIN_QUANTITY=1`, `CartItem.MAX_QUANTITY=99`. 도메인 메서드(`create`, `changeQuantity`, `increaseQuantity`)가 직접 검증하며 위반 시 `CartException`(`CART_ITEM_QUANTITY_EXCEEDED` 또는 `INVALID_CART_ITEM_QUANTITY`)을 4xx로 응답한다. DTO에는 `@Min(1) @Max(99)`로 Bean Validation도 부착하여 controller 진입 전에 거부한다.
- **근거**: cart는 재고를 차감하지 않으므로 재고 기반 검증은 cart 단계의 책임이 아니다. 재고 검증은 주문 시점에 수행된다. cart 단계의 상한은 abuse 방지와 UI 친화성의 가벼운 가드 역할만 한다.
- **결과**: 도메인 invariant가 자기 자신을 보호하고, Bean Validation이 1차 게이트로 작동한다. 재고 동시성은 본 phase 범위 밖이며 주문 단계 정책으로 유지된다.

## 결정 8: cart 항목 추가·수정 흐름은 낙관적 락 + retry + Processor 분리로 동시성을 처리한다

- **배경**: PR #166의 코드 리뷰(codex, Claude Code 양쪽)에서 `AddCartItemService`와 `UpdateCartItemQuantityService`의 동시성 결함이 지적되었다.
  - **update race**: 동일 회원·동일 productId의 기존 row에 동시 add 요청이 두 개 들어오면 두 트랜잭션이 같은 quantity를 fetch한 뒤 각자 `increaseQuantity`를 적용한다. dirty checking으로 UPDATE되면 마지막 commit만 반영되어 합산 quantity가 유실되고, 더 위험하게는 98 + 1 + 1 race에서 99 상한이 silent로 우회된다.
  - **insert race**: cart에 없는 productId에 동시 add 요청이 두 개 들어오면 둘 다 `Optional.empty()` 분기에서 `save`를 시도하고, 한쪽이 `uk_cart_item_member_product` UNIQUE 위반으로 500을 받는다.
  - 같은 양상의 race가 `UpdateCartItemQuantityService.update`(`changeQuantity`)에도 발생한다.

- **검토한 옵션**

  - **(A) 비관적 락 `@Lock(PESSIMISTIC_WRITE)`**: ADR-003(재고)과 일관된 패턴. cart 조회 시 row X-lock을 획득해 update를 직렬화. 평상시 매 요청 row lock overhead가 누적되는데, cart는 `(memberId, productId)` 단위라 contention이 거의 0이라 항상 락을 거는 비용이 합당하지 않다. update race만 해결하고 insert race는 ADR-011 안전망 500으로 별도 위임.

  - **(B) 낙관적 락 `@Version` + retry (채택)**: entity에 `@Version Long version` 추가. UPDATE 시 version 충돌이 `ObjectOptimisticLockingFailureException`으로 전파된다. 평상시 lock overhead가 없고, 충돌 시에만 retry. cart contention이 거의 0이라 retry 비용도 사실상 0. Order/Stock 도메인이 이미 같은 패턴(`StockConcurrencyService.decreaseWithOptimisticLock`)을 사용해 코드베이스 일관성이 있다. 도메인 메서드(`increaseQuantity`, `changeQuantity`, `validateQuantity`)를 그대로 사용해 invariant가 도메인에 단일 표현된다.

  - **(C) atomic UPDATE 쿼리 (`UPDATE ... SET quantity = quantity + :delta WHERE ... AND quantity + :delta <= 99`)**: 단일 SQL로 race-safe + retry 불필요. 가장 가볍다. 그러나 invariant(`<= 99`)가 SQL에 박혀 도메인 `validateQuantity`와 이중 표현된다. `@Modifying`이 JPA dirty checking을 우회해 `BaseTimeEntity.updated_at` 자동 갱신이 깨지고, affected rows = 0 분기에서 후속 SELECT가 필요해 흐름 복잡도가 늘어난다. `clearAutomatically=true`로 persistence context stale 회피도 필요하다.

  - **(D) insert-first → UNIQUE catch → addQuantity fallback**: 새 사용자(cart에 없는 경우)는 find 호출을 절약할 수 있다. 그러나 UNIQUE 위반 식별이 `org.hibernate.exception.ConstraintViolationException.getConstraintName()` 검사 등 Hibernate-specific에 의존해 fragile하다. Spring `@Transactional` 안에서 unchecked 예외가 던져지면 트랜잭션이 rollback-only로 마킹돼 같은 트랜잭션에서 두 번째 작업이 불가능하다. `Propagation.REQUIRES_NEW` 또는 빈 분리가 필요해 결국 (B)와 비슷한 구조가 된다. 두 번째 분기(`addQuantity`)도 여전히 update race-prone이라 (B)의 보호가 추가로 필요. 옵션 (B) 대비 장점이 없고 추가 함정만 있다.

- **결정**: (B) 낙관적 락 `@Version` + retry + Processor 분리를 채택한다.

  - `CartItem`에 `@Version Long version` 필드 추가.
  - `AddCartItemService`는 어노테이션 없는 outer 역할로 retry loop만 담당(`MAX_RETRY = 3`). 실제 트랜잭션은 신설 `AddCartItemProcessor`의 method-level `@Transactional`이 책임진다 (ADR-021).
  - `UpdateCartItemQuantityService`도 동일하게 `UpdateCartItemQuantityProcessor`와 짝을 이룬다.
  - Processor 안에서 `repository.save(entity)`를 명시 호출해 영속화 의도를 코드 표면에 드러낸다 (ADR-022).
  - retry catch 대상은 `ObjectOptimisticLockingFailureException`만이다. `DataIntegrityViolationException`(insert race)은 ADR-011 안전망 500 정책을 유지한다.

- **근거**

  - **평상시 비용 0**: cart는 `(memberId, productId)` 단위라 contention이 거의 0이므로 낙관적 락의 "충돌이 드물다" 전제가 정확히 들어맞는다. 평상시 흐름에서 락 획득/해제 비용이 0이다. ADR-003(재고 PESSIMISTIC)의 트레이드오프 — "높은 경쟁 상황에서 락 대기와 DB 부담" — 가 cart에는 적용 안 되고 단점만 떠안는 셈이다.
  - **invariant 단일 표현**: CLAUDE.md "비즈니스 로직은 Domain/application 계층" 원칙에 부합. atomic UPDATE(C)는 SQL과 도메인이 같은 invariant를 이중 표현해야 했다.
  - **코드베이스 일관성**: Order, Stock 도메인이 이미 `@Version`을 사용하고 retry loop 패턴(`StockConcurrencyService.decreaseWithOptimisticLock`)도 존재한다.
  - **명시적 영속화 호출**: ADR-022에 따라 `repository.save(entity)`를 명시 호출하므로 retry 흐름에서도 응용 코드의 영속화 의도가 코드 표면에 보존된다. dirty checking 묵시 의존을 끊는다.
  - **insert race를 안전망에 위임하는 이유**: cart insert race는 같은 사용자가 같은 productId를 처음 담는 순간에 ms 단위로 두 번 요청을 보내야 발생하는 극히 드문 시나리오다. 한 번 row가 생기면 이후 add는 update race 경로(B로 흡수)로 분기한다. `DataIntegrityViolationException`을 retry catch에 포함하면 UNIQUE 외 다른 무결성 위반(향후 컬럼/제약 추가 시)이 retry로 silent하게 묻힐 위험이 있어 ADR-011 정책을 그대로 유지한다.

- **트레이드오프**

  - `tbl_cart_item`에 `version BIGINT NOT NULL DEFAULT 0` 컬럼이 추가된다. 무중단 적용 가능하나 entity가 약간 무거워진다.
  - retry 중 DB 호출이 누적될 수 있다. 다만 cart contention이 극히 낮아 실측 충돌은 거의 발생하지 않을 것으로 예상되며, 평균 attempt 수는 1로 수렴한다.
  - insert race는 안전망 500으로 위임하므로 매우 드문 시점에 사용자가 한 번 더 add를 시도해야 할 수 있다. cart 특성상 두 번째 클릭은 이미 row가 생성된 상태라 update race(B로 흡수) 경로로 정상 동작한다.
  - cart UX상 insert race 멱등 흡수가 더 자연스럽다는 의견이 향후 강화되면, 본 결정 8을 재방문하여 (C) atomic UPDATE 또는 `ConstraintViolationException.getConstraintName()` 기반 정확한 UNIQUE 식별 + retry 방식으로 전환할 수 있다.

- **결과**

  - 동시 add: update race는 retry로 흡수, 합산 quantity와 99 상한 invariant가 모두 보장된다.
  - 동시 PATCH: race 발생 시 retry로 last-write-wins이 자연스럽게 달성된다(PATCH 의미가 절대값 변경).
  - DELETE는 `deleteByMemberIdAndProductId` atomic statement라 본 결정 영향 없음.
  - 본 결정은 cart phase에 한정되며, 다른 도메인의 ManyToOne→ID 마이그레이션 트랙(ADR-020 후속)에서 동일 패턴이 재사용될 수 있다.
  - retry 단위 테스트와 `concurrency` 태그 통합 테스트로 race 흡수와 한도 초과 시 throw를 검증한다.
