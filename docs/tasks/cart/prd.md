# 태스크 PRD

## 태스크명

- `cart`

## 배경

- 현재 시스템은 상품 조회에서 주문 생성으로 직진하는 흐름이며, 사용자가 구매 의사를 보관·편집할 수단이 없다.
- 로드맵(`docs/TEMP-TODO.md` Phase 2)과 PRD `MVP 제외 사항`(`docs/prd.md:16`)에서 장바구니가 별도 단계로 분리되어 있으나 구현되지 않았다.
- 본 태스크에서 회원 구매 흐름의 첫 단계인 장바구니를 도입해 이후 포인트·취소·결제 안정성 phase의 기반을 마련한다.

## 목표

- 회원이 cart에 상품을 담고, 수량을 변경/삭제하고, 내 cart를 조회할 수 있다.
- 주문이 성공하면 주문된 항목만 cart에서 자동으로 제거된다.
- 가격은 항상 최신 Product 가격으로 재조회되어 노출된다.

## 범위

- 포함 범위
  - cart 도메인 신설 (`com.commerce.cart`)
  - `POST /cart/items` (담기, UPSERT)
  - `GET /cart` (내 cart 조회 — 최신 가격 재조회, 합계, 구매 불가 마킹)
  - `PATCH /cart/items/{productId}` (수량 변경)
  - `DELETE /cart/items/{productId}` (항목 삭제)
  - 주문 생성 트랜잭션에서 주문된 productId만 cart에서 제거

- 제외 범위
  - 비로그인 게스트 cart 지원
  - cart 전체 비우기 API (`DELETE /cart`)
  - cart 항목 보관 기한(예: 90일) 자동 삭제
  - cart 단위 쿠폰/배송지/메모 등 메타데이터
  - 셀러별 cart 분리 (마켓플레이스 개념 없음)
  - 가격 변동 알림

## 주요 시나리오

- 로그인 사용자가 상품을 cart에 담는다. 이미 담아둔 상품이면 수량이 합산된다.
- 사용자가 cart를 조회하면 각 항목의 최신 가격·이름·이미지와 함께 합계가 응답된다.
- 사용자가 cart 항목의 수량을 변경하거나 삭제한다.
- 사용자가 주문을 생성하면 주문 성공 시 해당 항목만 cart에서 자동 제거되고, 미주문 항목은 유지된다.
- 상품이 판매 중지(`STOPPED`)되거나 soft delete되면 cart에는 `unavailable=true`로 표시되고 합계에서 제외된다.

## 요구사항

- cart API는 로그인 회원만 호출할 수 있어야 한다.
- 같은 회원이 같은 상품을 두 번 담으면 단일 row로 통합된다(`(member_id, product_id)` UNIQUE).
- 항목당 수량은 최소 1, 최대 99이며 초과 시 4xx로 거부한다.
- cart는 `productId`와 `quantity`만 저장하며 가격은 저장하지 않는다.
- 조회 시점에 최신 `Product` 가격으로 응답을 조립한다.
- 판매 중지/삭제된 상품은 응답에 `unavailable=true`로 표시하고 합계 계산에서 제외한다.
- 주문 생성이 성공하면 주문된 productId 목록만 cart에서 제거한다. cart에 없는 productId가 포함되어도 무방하다(0 row 삭제).
- cart 제거는 주문 트랜잭션 내에서 수행되며, 실패 시 주문도 함께 롤백된다.

## 제약사항

- Controller는 요청 검증, 서비스 위임, 응답 반환만 담당한다.
- 입력 검증은 DTO Bean Validation으로 일원화하며 controller에 if 검사를 두지 않는다.
- 비즈니스 로직은 Domain 또는 Application 계층에 둔다.
- `cart` 도메인은 다른 aggregate(Member, Product)를 객체로 직접 참조하지 않고 ID(`Long`)로만 식별한다.
- `order` 도메인은 `cart` 도메인을 직접 의존하지 않고 `order.application.port.CartItemRemover` 인터페이스로 의존한다.
- 기존 공통 응답 포맷 `ApiResponse<T>`를 유지한다.
- Hibernate `ddl-auto: update`로 새 테이블이 자동 생성된다(별도 마이그레이션 스크립트 없음).
