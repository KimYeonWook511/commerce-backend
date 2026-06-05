# Cart Retrospective

## 배경

`cart`는 회원의 구매 의사를 보관·편집할 수단을 도입하는 phase다. 로드맵 Phase 2 진입점이자 이후 포인트·취소·결제 안정성 phase의 기반이 되는 단계로, 시스템이 처음으로 "상품 조회 → 주문 생성" 직선 흐름에서 벗어나 사용자가 임시 보관 상태를 유지하는 도메인을 다뤘다.

본 phase는 단순히 새로운 CRUD API 4종을 더한 것이 아니라, 신규 도메인의 cross-aggregate 참조 정책(ADR-020)을 정통 DDD 원칙에 맞춰 명문화한 첫 사례라는 의의가 있다.

## 태스크 개요

다음 산출물을 생성·통합했다.

- 신규 도메인 `com.commerce.cart`
  - `CartItem` entity (단일 entity aggregate, `memberId`/`productId`/`quantity`)
  - `CartItemRepository` port + JPA adapter
  - `CartException` / `CartErrorCode` (`INVALID_CART_ITEM_QUANTITY`, `CART_ITEM_QUANTITY_EXCEEDED`, `CART_ITEM_NOT_FOUND`)
- 회원용 API 4종
  - `POST /cart/items` (UPSERT 담기)
  - `GET /cart` (최신 가격 재조회 + `unavailable` 마킹 + `totalAmount`)
  - `PATCH /cart/items/{productId}` (수량 절대값 변경)
  - `DELETE /cart/items/{productId}` (멱등 삭제)
- 주문-cart 연동
  - `order.application.port.CartItemRemover` 인터페이스 (order 도메인)
  - `cart.infrastructure.CartItemRemoverAdapter` 구현체 (cart 도메인)
  - `OrderCreateProcessor`에서 주문 저장 직후 동일 트랜잭션 내 cart 제거 호출
- 신규 테이블 `tbl_cart_item` (PK + `member_id`/`product_id` UNIQUE 복합 인덱스, FK 없음)
- 루트 docs 5종 동기화 + ADR-020 명문화

### 적용한 7개 결정 (요약)

| # | 결정 | 핵심 |
|---|---|---|
| 1 | CartItem-only 단일 entity aggregate | Cart aggregate root 미생성. 사용자 cart = `findAllByMemberId` 결과 list. |
| 2 | cart는 cross-aggregate를 ID(`Long`)로만 참조 | `@ManyToOne`/`@JoinColumn` 미사용. ADR-020으로 신규 도메인 정책 명문화. |
| 3 | 가격은 조회 시점에 Product 재조회 | `CartItem`은 `productId`/`quantity`만 저장. 가격 동기화 책임 회피. |
| 4 | 주문 생성 트랜잭션 내 cart 제거 | RDB 동일 트랜잭션. cart 제거 실패 시 주문도 롤백. |
| 5 | 주문 시 cart 존재 검증 안 함 | Buy Now, 재시도 등 정상 경로 차단 방지. cart는 권한 원천 아님. |
| 6 | 구매 불가 상품은 `unavailable=true` 마킹 | row 자동 삭제 없음. 조회 endpoint side effect 회피(CQS). |
| 7 | 항목당 수량 상한 MIN=1, MAX=99 도메인 강제 | 재고 검증은 주문 단계. cart 상한은 abuse 방지 가벼운 가드. |

ADR-020 "신규 도메인의 cross-aggregate 참조는 ID로 한다"를 본 phase에서 루트 `docs/adr.md`에 누적 추가했다.

## 단계별 작업 요약

### Step 1: cart-item-domain-and-repository

`com.commerce.cart` 도메인을 신설했다. `CartItem` entity가 `BaseTimeEntity`를 상속하고 `(member_id, product_id)` UNIQUE 제약을 선언했다. `MIN_QUANTITY=1`, `MAX_QUANTITY=99` 상수를 두고 `create`/`changeQuantity`/`increaseQuantity` 도메인 메서드가 자체적으로 invariant를 검증한다.

repository는 port + adapter로 분리했다. `JpaCartItemRepository`는 Spring Data JPA의 자동 쿼리 유도 기능으로 `findByMemberIdAndProductId`/`findAllByMemberId`/`deleteByMemberIdAndProductId`/`deleteByMemberIdAndProductIdIn` 시그니처를 제공한다. UNIQUE race 충돌은 ADR-011 안전망 500으로 위임했다(adapter에서 catch 안 함).

테스트 인프라로 `CleanupOrder`에 `CART(25)` enum 항목을 추가하고 `CartPersistenceTestSupport`를 신설했다. `CartItemTest` 12개(quantity invariant 경계값), `CartItemRepositoryAdapterTest` 5개(`@DataJpaTest` 슬라이스)로 검증했다.

### Step 2: cart-add-and-list-api

`POST /cart/items`와 `GET /cart`를 추가했다. `AddCartItemService`는 ADR-011 find-first 패턴으로 UPSERT를 처리한다(없으면 save, 있으면 `increaseQuantity`). `GetMyCartService`는 `@Transactional(readOnly=true)`로 cart row를 조회한 뒤 `productRepository.findAllById`로 최신 Product를 한 번에 조립하고, `status == STOPPED` 또는 `deletedAt != null`인 경우 `unavailable=true`로 마킹하며 `totalAmount`에서 제외한다. Product가 누락된 항목(예: hard delete된 상품)은 WARN 로그 후 응답에서 제외한다.

`CartController`는 `@AuthenticatedMemberId`로 `memberId`를 주입받고 `@Valid @RequestBody`로 검증을 일원화한다. controller에 if 검사를 두지 않는다는 원칙을 지켰다.

`AddCartItemServiceTest` 3개, `GetMyCartServiceTest` 4개, `CartControllerTest` 5개(@WebMvcTest 슬라이스)로 검증했다.

### Step 3: cart-update-and-delete-api

`PATCH /cart/items/{productId}`와 `DELETE /cart/items/{productId}`를 추가했다. `UpdateCartItemQuantityService`는 find-then-`changeQuantity` 흐름으로 절대값 변경을 처리하고 미존재 시 `CART_ITEM_NOT_FOUND`를 던진다. `RemoveCartItemService`는 `deleteByMemberIdAndProductId`로 멱등 삭제를 수행한다(미존재여도 성공 응답).

`CartItemUpdateRequest`(`@NotNull @Min(1) @Max(99) quantity`) DTO를 신설하고 controller에서 동일하게 `@Valid @RequestBody`로 검증한다.

`UpdateCartItemQuantityServiceTest` 3개, `RemoveCartItemServiceTest` 2개, `CartControllerTest` 확장 5개(PATCH 정상/quantity=0/quantity=100, DELETE 정상/미존재)로 검증했다.

### Step 4: order-cart-clear-integration

order 도메인이 cart 도메인을 직접 의존하지 않도록 `order.application.port.CartItemRemover` 인터페이스를 order 패키지에 신설하고, 구현체 `CartItemRemoverAdapter`를 cart 패키지에 두었다. 빈 productIds 목록은 즉시 return해 불필요한 DB 호출을 회피한다.

`OrderCreateProcessor`가 주문 저장 직후 동일 트랜잭션 안에서 `cartItemRemover.removeByMemberAndProducts(memberId, productIds)`를 호출한다. `OrderCreateService`의 멱등 응답 경로(Redis hit 또는 DB hit)는 `OrderCreateProcessor.execute`를 호출하지 않으므로 첫 요청에서만 cart가 제거된다(별도 가드 불필요).

`OrderCreateProcessorTest`에 `CartItemRemover` mock을 주입해 verify를 추가하고, `OrderCreateCartIntegrationTest` 3개(주문 성공 시 주문 항목만 제거·미주문 유지, Buy Now 시나리오, 재고 부족 시 cart 롤백)로 통합 검증했다.

### Step 5: sync-root-docs

루트 docs를 cart phase 산출물에 맞춰 동기화했다.

- `docs/prd.md`: MVP 제외 사항에서 장바구니를 제거하고 핵심 기능에 추가
- `docs/architecture.md`: 패키지 트리에 `cart/` 추가, 도메인별 주요 서비스 표에 cart 4개 서비스 등록, 데이터 흐름에 cart CRUD 4개와 주문 생성의 `CartItemRemover` 호출 추가, 도메인 이벤트 INFO 로그 적용 범위를 8개 도메인 17개 컴포넌트로 갱신
- `docs/api-spec.md`: cart API 4종 스펙 추가
- `docs/db-schema.md`: `tbl_cart_item`과 `uk_cart_item_member_product` UNIQUE 제약, FK 미사용 정책(ADR-020) 명시
- `docs/adr.md`: **ADR-020 "신규 도메인의 cross-aggregate 참조는 ID로 한다"** 누적 추가

워크스페이스 공유 docs(`api-contract.md`, `progress.md`)는 backend 세션 책임 범위 밖이므로 건드리지 않았다.

## 설계 결정의 회고

### CartItem-only 단일 entity aggregate 선택

PRD 범위에서 cart 자체에 부착되는 메타데이터가 전무했다. Cart aggregate root를 만들었다면 `(id, memberId, createdAt, updatedAt)`만 가진 빈 컨테이너가 됐을 것이다. `StockHistory`, `RefreshToken` 같은 기존 단일 entity aggregate 패턴이 이미 코드베이스에 있어 결정에 자신감이 있었다.

확장 시 고려사항: 셀러별 cart 분리, 위시리스트, 쿠폰 슬롯, cart 만료 같은 cart 레벨 메타데이터가 미래에 도입되면 Cart aggregate root를 추가하고 CartItem에 `cartId` FK를 붙이는 마이그레이션이 자연스럽다. 그때 ADR-020 정책과의 호환성(같은 aggregate 내부는 객체 참조 허용)도 함께 정리해야 한다.

### ADR-020 도입의 의의

기존 코드베이스의 `Order.member`, `OrderItem.product`, `Stock.product` 등은 `@ManyToOne` 객체 참조를 사용해 도메인과 application 인터페이스 사이에 이중 표현이 있었다. 본 phase에서 신규 도메인을 만들면서 정통 DDD "Reference Other Aggregates Only By Identity" 원칙을 적용하고, 이를 루트 ADR에 명문화한 첫 사례가 됐다.

이 결정으로 cart 조회 시 `productRepository.findAllById(productIds)`를 명시적으로 호출하게 됐는데, 결과적으로 "가격을 조회 시점에 재조회한다"(결정 3)는 정책과 자연스럽게 맞물려 도메인이 단순해졌다. 도메인 결합도와 단위 테스트 부담도 줄었다.

후속 트랙으로 기존 Order/Stock/StockHistory의 ManyToOne을 ID 참조로 마이그레이션하는 별도 phase가 필요하다. 단, JPA lifecycle, fetch join, cascading 영향을 고려해 신중히 분리해야 하므로 본 phase 범위에는 포함하지 않았다.

### 주문-cart 동일 트랜잭션 결정의 트레이드오프

ADR-005가 "외부 시스템 연동은 AFTER_COMMIT 이벤트로 분리"를 가리키지만, cart와 order 모두 동일 RDB이므로 ADR-005의 적용 대상이 아니라고 판단했다. cart `DELETE WHERE` 자체가 매우 가벼워 실패 확률이 낮고, "주문 성공했는데 cart에 그대로 남는" 일시 불일치를 만들지 않는 정합성이 더 중요했다.

트레이드오프: cart 제거가 어떤 이유로든 실패하면 주문이 함께 롤백된다. 실패 가능성이 매우 낮아 받아들일 수 있다고 판단했지만, 만약 cart 도메인이 별도 DB로 분리되거나(예: 마이크로서비스화) 외부 시스템에 의존하게 되면 즉시 AFTER_COMMIT 모델로 재검토해야 한다.

### Buy Now 호환을 위해 cart 존재 검증을 제외한 결정

초기에는 TEMP-TODO의 "주문 생성 시 장바구니 기반 검증" 항목을 보고 주문 요청 productId가 cart에 있어야만 주문을 허용하는 안도 검토했다. 그러나 이렇게 강제하면 "지금 구매(Buy Now)", 주문 재시도 등 정상 흐름을 차단해버린다.

결국 cart는 사용자 편의의 임시 보관소이지 주문 권한의 원천이 아니라는 관점으로 정리했다. 결정 5의 효과로 모든 정상 주문 경로(cart 경유/Buy Now/재시도)가 동일한 흐름을 따르게 됐고, `deleteByMemberIdAndProductIdIn`은 cart에 없는 productId가 포함되어도 0 row 삭제로 자연 처리된다.

## 잘된 점

- 단계별 step이 작게 분리되어 step별 AC를 깔끔하게 통과시켰다(step1 도메인/repository → step2 담기/조회 → step3 수정/삭제 → step4 통합 → step5 docs).
- ADR을 먼저 정리하고 step에 들어간 결과, 구현 중 "이건 어떻게 처리해야 할까" 같은 판단 비용이 거의 없었다.
- 주문-cart 연동을 port + adapter로 분리해 order 도메인이 cart 도메인을 모르도록 유지했다. 패키지 의존 방향이 cart → order 단방향이 됐다.
- `unavailable` 마킹과 `totalAmount` 계산 로직을 application 계층 result 조립 시점에 한 번만 처리하도록 모았다. controller/repository에 새지 않았다.
- step5에서 루트 docs 5종을 한꺼번에 동기화해 API/스키마/아키텍처/PRD가 최신 코드와 어긋나는 시간 창을 만들지 않았다.

## 어려웠던 점

- 결정 5(cart 존재 검증 제외)의 근거를 처음 정리할 때 "TEMP-TODO에 검증 항목이 있는데 왜 안 하는가"를 ADR에 명확히 설명해야 했다. Buy Now 시나리오를 명시적으로 인용하지 않으면 누군가 미래에 "검증을 추가해야 한다"고 회귀시킬 수 있어 ADR-007 결정 5에 결과 시나리오까지 적어둔 이유다.
- 결정 4(동일 트랜잭션)와 결정 6(`unavailable` 마킹)는 ADR-005 패턴과 표면적으로 충돌하는 것처럼 보여, ADR에 "왜 ADR-005가 적용 안 되는가"를 의식적으로 명시해야 했다.
- ADR-020을 본 phase의 ADR로만 두지 않고 루트 `docs/adr.md`에 누적 추가하는 결정이 phase 도중에 명확해졌다. 신규 도메인 기본값이라는 의미가 phase 산출물에 머물면 후속 phase에서 같은 논의가 반복될 위험이 있었다.

## 후속 과제

| 우선순위 | 항목 | 메모 |
|---|---|---|
| P1 | 기존 Order/Stock/StockHistory ManyToOne → ID 참조 마이그레이션 | ADR-020 후속 트랙. JPA lifecycle/fetch join 영향 신중히 분리. |
| P2 | cart 보관 기한 자동 삭제(예: 90일) | 현재 row가 무한 보존됨. batch job 또는 Spring TaskScheduler 도입 검토. |
| P2 | cart 전체 비우기 API (`DELETE /cart`) | 현재는 항목별 삭제만 가능. UX 요구가 생기면 추가. |
| P2 | 가격 변동 알림 정책 | 현재는 조회 시점 재조회만. 알림이 필요하면 별도 phase. |
| P3 | Flyway 도입 후 `tbl_cart_item` 마이그레이션 스크립트 작성 | 현재는 `ddl-auto: update`. ADR-018 후속 트랙. |
| P3 | 비로그인 게스트 cart 지원 | 본 phase 제외 범위. Redis 기반 익명 세션 + 로그인 시 병합 정책 필요. |

## 측정

- 테스트
  - `CartItemTest` 12개 (도메인 invariant)
  - `CartItemRepositoryAdapterTest` 5개 (@DataJpaTest 슬라이스)
  - `AddCartItemServiceTest` 3개 / `GetMyCartServiceTest` 4개 / `UpdateCartItemQuantityServiceTest` 3개 / `RemoveCartItemServiceTest` 2개 (application unit)
  - `CartControllerTest` 10개 (@WebMvcTest 슬라이스, step2 5 + step3 5)
  - `OrderCreateCartIntegrationTest` 3개 (통합)
  - `OrderCreateProcessorTest`에 `CartItemRemover` verify 케이스 추가
  - cart 범위 합계 약 42개 (controller/service/domain/repository/통합)
- 신규 코드 파일 (main)
  - cart 도메인 14개 (domain 2, exception 2, infrastructure 3, application 7, presentation 3 — request 2 / controller 1)
  - order 도메인 1개 (`CartItemRemover` port)
- 신규 테이블 1개 (`tbl_cart_item`) / UNIQUE 인덱스 1개 (`uk_cart_item_member_product`) / FK 0개

## 얻은 교훈

- 신규 도메인을 만들 때는 **참조 정책을 먼저 결정**해야 한다. ADR-020을 phase 도중에 정리해서 다행이었지만, 더 일찍 결정했다면 기존 도메인의 ManyToOne 마이그레이션 트랙도 같이 계획할 수 있었을 것이다.
- "외부 시스템이 아닌 동일 RDB 도메인" 같은 경계 조건을 ADR에 명시적으로 적어두면, 표면적으로 충돌하는 정책(ADR-005 vs 결정 4) 사이의 판단 근거가 명확해진다.
- TEMP-TODO 항목을 무비판적으로 구현하지 않고, **PRD/사용자 흐름과 충돌하는지** 먼저 확인하는 것이 중요하다(결정 5). 본 phase에서는 "Buy Now 호환"이라는 사용자 흐름이 검증 항목을 무력화하는 강한 근거였다.
- `unavailable` boolean 하나로 끝낸 결정 6은 "내부 상태 표면 노출 최소화" 원칙과 사용자 UX 모두를 만족시키는 가벼운 설계였다. 미래에 "왜 unavailable인가" 사유 코드가 필요해지면 그때 추가하면 된다(YAGNI).
- 단일 entity aggregate 선택은 **현재 도메인 범위**에 충실한 결정이었다. 미래 확장은 마이그레이션으로 자연 해결 가능하다는 backup plan이 있어야 안심하고 단순화할 수 있다.
