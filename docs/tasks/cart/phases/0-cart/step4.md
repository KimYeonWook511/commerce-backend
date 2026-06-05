# Step 4: order-cart-clear-integration

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cart/prd.md`
- `/docs/tasks/cart/architecture.md`
- `/docs/tasks/cart/adr.md` (결정 4, 결정 5)
- `/docs/architecture.md` (port + adapter 패턴, 트랜잭션 정책)
- `/docs/adr.md` (ADR-002 멱등, ADR-005 AFTER_COMMIT 정책, ADR-011 find-first)
- `/docs/tasks/cart/phases/0-cart/step1.md`
- `/docs/tasks/cart/phases/0-cart/step2.md`
- `/docs/tasks/cart/phases/0-cart/step3.md`
- `/src/main/java/com/commerce/order/application/OrderCreateService.java`
- `/src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `/src/main/java/com/commerce/order/application/command/OrderCreateCommand.java`
- `/src/main/java/com/commerce/order/application/command/OrderCreateItem.java`
- `/src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java` (port 패턴 참고)
- `/src/main/java/com/commerce/cart/domain/repository/CartItemRepository.java`
- `/src/main/java/com/commerce/cart/infrastructure/CartItemRepositoryAdapter.java`
- `/src/test/java/com/commerce/order/application/` (기존 OrderCreate 테스트 위치)
- `/src/test/java/com/commerce/support/CleanupOrder.java`

## 작업

주문 생성 트랜잭션 안에서 cart 항목을 제거하도록 연동한다.

### Port 도입

- 위치: `src/main/java/com/commerce/order/application/port/CartItemRemover.java`
- 시그니처
  ```java
  public interface CartItemRemover {
      void removeByMemberAndProducts(Long memberId, List<Long> productIds);
  }
  ```
- order 도메인이 cart 도메인 구현체에 직접 의존하지 않도록 한다(ADR 아키텍처 규칙).

### Adapter 구현

- 위치: `src/main/java/com/commerce/cart/infrastructure/CartItemRemoverAdapter.java`
- `@Component @RequiredArgsConstructor implements CartItemRemover`
- 의존: `CartItemRepository`
- 흐름: `cartItemRepository.deleteByMemberIdAndProductIdIn(memberId, productIds)`
- `productIds`가 비어 있으면 즉시 return (불필요한 쿼리 방지)
- 로그: 필요 없음 (cart 도메인의 변경 이벤트 로그는 cart application service가 책임지나, 본 adapter는 호출 경로이므로 INFO 로그 생략. WARN/ERROR가 필요할 경우만 추가.)

### `OrderCreateProcessor` 변경

- 위치: `src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- 의존성 주입: `private final CartItemRemover cartItemRemover;` 추가
- 주문 저장 완료 직후, 동일 트랜잭션 내에서 호출
  ```java
  List<Long> productIds = command.getItems().stream()
      .map(OrderCreateItem::getProductId)
      .toList();
  cartItemRemover.removeByMemberAndProducts(command.getMemberId(), productIds);
  ```
- 호출 위치는 주문 INFO 로그 직전/직후, 결제 준비(`PaymentReadyService`) 호출 흐름과의 순서를 깨지 않도록 결정한다. **권장**: 주문 + 주문항목 저장 완료 후, 결제 준비 호출 이전에 cart 제거. 단, 같은 트랜잭션 안이라면 순서는 정합성 측면에서 동등하므로 가독성 위주로 배치한다.

### 기존 테스트 영향

- `OrderCreateProcessorTest` 단위 테스트: `CartItemRemover` Mockito mock 주입 추가. `verify`로 호출 확인.
- 주문 생성 통합 테스트(`OrderCreateService*IntegrationTest` 등)는 cart 테이블 cleanup이 자동으로 적용되도록 `CleanupOrder.CART`가 등록돼 있는지 재확인 (Step 1에서 추가됨).
- 기존 주문 생성 통합 테스트는 cart가 비어 있는 상태로 시작하므로 0 row 삭제로 자연스럽게 통과해야 한다.

### 통합 테스트 신규

- 위치: `src/test/java/com/commerce/order/application/OrderCreateCartIntegrationTest.java` (또는 cart 쪽에 둘 수 있음. 주문 흐름 관점이라 order 쪽 권장)
- `@SpringBootTest` 또는 `@DataJpaTest + @Import` 조합 — 기존 통합 테스트 컨벤션 따른다
- Testcontainers 기반 (DB 필요)
- 시나리오
  - 사용자가 cart에 productA(qty 1), productB(qty 2), productC(qty 3) 담은 상태
  - productA, productB만 포함된 주문 생성 성공
  - cart에 productC만 남고 A, B는 제거되었는지 확인
  - cart에 없는 productId가 주문에 포함된 케이스(=Buy Now 시나리오): 정상 주문 + cart 변동 없음
  - 주문 실패 시(예: 재고 부족) cart 그대로 유지되는지 확인 (트랜잭션 롤백)

## 수정 가능 경로

- `src/main/java/com/commerce/order/application/port/CartItemRemover.java`
- `src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `src/main/java/com/commerce/cart/infrastructure/CartItemRemoverAdapter.java`
- `src/test/java/com/commerce/order/**`
- `src/test/java/com/commerce/cart/**`
- `docs/tasks/cart/**`

## Acceptance Criteria

```bash
./gradlew test
```

(`OrderCreateProcessor` 변경은 회귀 영향이 크므로 전체 테스트로 확인.)

추가 검증:

```bash
rg "CartItemRemover" src/main/java
```

(port와 adapter 양쪽이 모두 존재하는지 확인.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `order` 패키지가 `com.commerce.cart.*`를 직접 import하지 않는가? (port 분리 검증)
   - `CartItemRemoverAdapter`가 `@Component`로 빈 등록되어 있고 `CartItemRepository`를 사용하는가?
   - `OrderCreateProcessor`가 같은 트랜잭션 내에서 cart 제거를 호출하는가? AFTER_COMMIT 이벤트 분리하지 않았는가?
   - 멱등 응답 경로(`OrderCreateService.attemptCreateOrder` 미호출 경로)에서 cart 제거가 호출되지 않는가?
   - 기존 주문 생성 회귀 테스트가 모두 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `@TransactionalEventListener(AFTER_COMMIT)`으로 cart 제거를 분리하지 마라. 이유: 두 도메인 모두 RDB이고 정합성 측면에서 동일 트랜잭션이 자연스럽다(ADR 결정 4).
- `OrderCreateProcessor`가 `com.commerce.cart.*`를 직접 import하지 마라. 이유: ADR 결정 4의 port 분리 원칙.
- cart에 주문 productId가 존재하는지 사전 검증을 추가하지 마라. 이유: ADR 결정 5(주문은 cart와 독립). "Buy Now" 흐름을 차단해서는 안 된다.
- 멱등 응답 경로에 cart 제거 추가 호출을 두지 마라. 이유: 첫 요청 트랜잭션에서 이미 처리됐다.
- 기존 테스트를 깨뜨리지 마라.
