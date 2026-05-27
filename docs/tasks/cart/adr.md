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

## 결정 6: 구매 불가 상품(STOPPED / soft-deleted)은 응답에 `unavailable=true`로 표시하고 cart row는 보존한다

- **배경**: cart에 담아둔 상품이 판매 중지되거나 soft delete된 경우 (a) cart에서 자동 제거, (b) 응답에서 제외만, (c) `unavailable` 마킹하여 노출 세 가지를 비교했다.
- **결정 내용**: cart 응답의 각 항목에 `unavailable` boolean을 둔다(`status == STOPPED || deletedAt != null`). `totalAmount`는 `unavailable=false` 항목만 합산한다. cart row는 자동 삭제하지 않는다. 사유 코드는 노출하지 않고 boolean 플래그만 제공한다.
- **근거**: 한국 이커머스(올리브영 등)의 일반 UX와 부합한다. 사용자가 "내가 담은 상품이 사라진" 혼란을 겪지 않고 직접 삭제할 수 있다. 조회 endpoint에서 자동 삭제(side effect)는 CQS 위반이라 채택하지 않는다. 사유 코드는 내부 product 상태가 외부로 새는 표면을 줄이기 위해 boolean만 노출한다.
- **결과**: cart 데이터는 보존되고 UI는 boolean 플래그로 분기한다. 사용자가 명시적으로 삭제 호출 시에만 row가 사라진다.

## 결정 7: 항목당 고정 수량 상한(MIN=1, MAX=99)을 도메인에서 강제한다

- **배경**: 수량 상한 정책으로 (a) 항목당 고정 상한, (b) 상품 재고까지 허용(이중 검증), (c) 상한 없음을 비교했다.
- **결정 내용**: `CartItem.MIN_QUANTITY=1`, `CartItem.MAX_QUANTITY=99`. 도메인 메서드(`create`, `changeQuantity`, `increaseQuantity`)가 직접 검증하며 위반 시 `CartException`(`CART_ITEM_QUANTITY_EXCEEDED` 또는 `INVALID_CART_ITEM_QUANTITY`)을 4xx로 응답한다. DTO에는 `@Min(1) @Max(99)`로 Bean Validation도 부착하여 controller 진입 전에 거부한다.
- **근거**: cart는 재고를 차감하지 않으므로 재고 기반 검증은 cart 단계의 책임이 아니다. 재고 검증은 주문 시점에 수행된다. cart 단계의 상한은 abuse 방지와 UI 친화성의 가벼운 가드 역할만 한다.
- **결과**: 도메인 invariant가 자기 자신을 보호하고, Bean Validation이 1차 게이트로 작동한다. 재고 동시성은 본 phase 범위 밖이며 주문 단계 정책으로 유지된다.
