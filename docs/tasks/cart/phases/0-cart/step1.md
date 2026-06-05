# Step 1: cart-item-domain-and-repository

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cart/prd.md`
- `/docs/tasks/cart/architecture.md`
- `/docs/tasks/cart/adr.md`
- `/docs/tasks/cart/api-spec.md`
- `/docs/tasks/cart/db-schema.md`
- `/docs/architecture.md`
- `/docs/testing-conventions.md`
- `/docs/adr.md` (ADR-011 find-first 패턴, ADR-018 enum 매핑)
- `/src/main/java/com/commerce/common/jpa/BaseTimeEntity.java`
- `/src/main/java/com/commerce/stock/domain/StockHistory.java` (단일 entity aggregate 참고)
- `/src/main/java/com/commerce/order/domain/Order.java` (entity 작성 패턴 참고)
- `/src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `/src/main/java/com/commerce/order/infrastructure/JpaOrderRepository.java`
- `/src/main/java/com/commerce/order/infrastructure/OrderRepositoryAdapter.java`
- `/src/main/java/com/commerce/order/exception/OrderException.java`
- `/src/main/java/com/commerce/order/exception/OrderErrorCode.java`
- `/src/test/java/com/commerce/support/CleanupOrder.java`
- `/src/test/java/com/commerce/support/PersistenceTestSupport.java`

## 작업

신규 `com.commerce.cart` 도메인의 entity, 예외, repository(port + adapter), 테스트 인프라를 추가하라.

### `CartItem` entity

- 위치: `src/main/java/com/commerce/cart/domain/CartItem.java`
- 테이블: `tbl_cart_item`
- `extends BaseTimeEntity`
- 컬럼
  - `id` (PK, IDENTITY)
  - `memberId: Long` (`@Column(name = "member_id", nullable = false)`) — FK 미사용, ID만 저장 (ADR-020)
  - `productId: Long` (`@Column(name = "product_id", nullable = false)`) — FK 미사용
  - `quantity: int` (`@Column(nullable = false)`)
- 제약: `@Table(uniqueConstraints = @UniqueConstraint(name = "uk_cart_item_member_product", columnNames = {"member_id", "product_id"}))`
- 상수: `public static final int MIN_QUANTITY = 1;`, `public static final int MAX_QUANTITY = 99;`
- 도메인 메서드
  - `public static CartItem create(Long memberId, Long productId, int quantity)` — 생성자 호출 + 검증
  - `public void changeQuantity(int quantity)` — 절대값 변경 + 검증
  - `public void increaseQuantity(int delta)` — 합산 결과 검증 (현재 quantity + delta가 MAX 초과 시 `CART_ITEM_QUANTITY_EXCEEDED`, MIN 미만이면 `INVALID_CART_ITEM_QUANTITY`)
  - `private static void validateQuantity(int quantity)` — 미만/초과에 따라 적절한 errorCode로 `CartException` throw
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@Getter`, `@Builder` private constructor 패턴은 기존 `Order`, `Product` 패턴 그대로 따른다.

### 예외

- `src/main/java/com/commerce/cart/exception/CartException.java` — 기존 `OrderException` 패턴 그대로
- `src/main/java/com/commerce/cart/exception/CartErrorCode.java` — 기존 `OrderErrorCode` 패턴
  - `INVALID_CART_ITEM_QUANTITY` (400)
  - `CART_ITEM_QUANTITY_EXCEEDED` (400)
  - `CART_ITEM_NOT_FOUND` (404)

### Repository

- 도메인 port: `src/main/java/com/commerce/cart/domain/repository/CartItemRepository.java`
  - `CartItem save(CartItem cartItem)`
  - `Optional<CartItem> findByMemberIdAndProductId(Long memberId, Long productId)`
  - `List<CartItem> findAllByMemberId(Long memberId)`
  - `void deleteByMemberIdAndProductId(Long memberId, Long productId)`
  - `void deleteByMemberIdAndProductIdIn(Long memberId, List<Long> productIds)`
- Spring Data JPA: `src/main/java/com/commerce/cart/infrastructure/JpaCartItemRepository.java extends JpaRepository<CartItem, Long>` — 위 메서드 시그니처(자동 쿼리 유도) 또는 `@Modifying @Query`로 bulk delete 구현 선택
- Adapter: `src/main/java/com/commerce/cart/infrastructure/CartItemRepositoryAdapter.java implements CartItemRepository` — `@Component` + JPA repository 위임 (`Order` 패턴 동일)

### 테스트 인프라

- `src/test/java/com/commerce/support/CleanupOrder.java`에 `CART` enum 항목 추가 (`value`는 기존 enum 흐름에 맞춰 ORDER보다 작거나 동일한 우선순위로 배치 — FK가 없으므로 어느 순서든 동작하나 `STOCK`/`PAYMENT` 같이 자식 성격으로 ORDER 앞이 자연스러움. 구체 value는 기존 흐름 보고 적절히 부여)
- `src/test/java/com/commerce/cart/infrastructure/persistence/support/CartPersistenceTestSupport.java` 신설 — 기존 `OrderPersistenceTestSupport` 패턴 따라 `PersistenceTestSupport` 구현, `cleanupOrder()` 반환, cart 테스트 데이터 헬퍼 메서드 제공

### 도메인 단위 테스트

- 위치: `src/test/java/com/commerce/cart/domain/CartItemTest.java`
- 검증
  - 생성 시 정상 값 통과
  - 생성 시 quantity < 1 → `INVALID_CART_ITEM_QUANTITY`
  - 생성 시 quantity > 99 → `CART_ITEM_QUANTITY_EXCEEDED`
  - `changeQuantity` 정상/경계/위반
  - `increaseQuantity` 정상/합산 > 99 거부

### Adapter 슬라이스 테스트

- 위치: `src/test/java/com/commerce/cart/infrastructure/CartItemRepositoryAdapterTest.java`
- `@DataJpaTest` 슬라이스로 다음 검증
  - `save` 후 `findByMemberIdAndProductId` 정상 조회
  - `findAllByMemberId`로 다른 회원 데이터 격리 확인
  - `(member_id, product_id)` UNIQUE 위반 시 `DataIntegrityViolationException` 발생
  - `deleteByMemberIdAndProductIdIn`로 일괄 삭제

## 수정 가능 경로

- `src/main/java/com/commerce/cart/**`
- `src/test/java/com/commerce/cart/**`
- `src/test/java/com/commerce/support/CleanupOrder.java`
- `docs/tasks/cart/**`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.cart.*'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - architecture.md 디렉토리 구조(`domain/`, `infrastructure/`, `exception/`)를 따르는가?
   - `BaseTimeEntity` 상속, `@JdbcTypeCode` 미사용(enum 없음), `@NoArgsConstructor(PROTECTED)` 등 기존 entity 컨벤션을 따랐는가?
   - 도메인 메서드가 invariant 검증을 자체적으로 수행하는가?
   - Repository는 port + adapter 분리가 되어 있는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `@ManyToOne`, `@JoinColumn`, `@OneToMany`를 사용하지 마라. 이유: 본 phase 결정(ADR-020)이 cross-aggregate 참조를 `Long` ID로만 다룬다.
- `CartItem`에 가격, 상품명 등 Product 정보를 컬럼으로 저장하지 마라. 이유: 가격 동기화는 조회 시점 재조회 정책이며 cart 단계의 책임이 아니다.
- `DuplicateKeyException`을 Adapter에서 catch하지 마라. 이유: ADR-011 find-first + 안전망 500 정책.
- `CartItem` 외에 별도 `Cart` aggregate root를 만들지 마라. 이유: 본 phase 결정(CartItem-only 단일 entity aggregate).
- 컨트롤러/서비스 코드를 작성하지 마라. 이유: 본 step은 도메인 + 레포지토리 + 테스트 인프라 한정.
- 기존 테스트를 깨뜨리지 마라.
