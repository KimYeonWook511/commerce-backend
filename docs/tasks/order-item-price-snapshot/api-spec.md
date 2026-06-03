# 태스크 API 스펙

## 개요

- 본 task 는 API 계약을 변경하지 않는다.
- `OrderItem.unitPrice` 는 entity / schema 까지만 추가하고 응답 DTO 에 노출하지 않는다 (task adr 결정 3).

## Request / Response 변경

- 없음.
- `POST /orders` 등 주문 생성 API 의 요청 / 응답 형식은 그대로 유지된다.
- `OrderCreateResult`, `OrderCancelResult` 등 응답 DTO 는 변경하지 않는다.
- `PaymentReadyService` 도 기존대로 `order.getTotalPrice()` 만 사용한다.

## Controller / Application 시그니처 변경

- 없음.
- `Order.addOrderItem(Long productId, int quantity, int unitPrice)` 외부 시그니처는 PR #200 에서 자리잡은 형태 그대로 유지된다.
- `OrderItem.of(Order, Long productId, int quantity, int unitPrice)` 는 시그니처가 확장되지만 호출자는 `Order.addOrderItem` 내부 1곳뿐이라 외부 영향이 없다.

## 영향 받지 않는 응답 흐름 (재확인)

- 주문 생성: `OrderCreateResult` 는 `orderId / totalPrice / status` 만 노출.
- 주문 취소: `OrderCancelResult` 는 `orderId / status` 만 노출.
- 결제 준비: `PaymentReadyResult` 는 `totalPayAmount` (= `order.getTotalPrice()`) 와 productName / productCount 사용. OrderItem.unitPrice 미사용.

## 후속 정비 (본 task 범위 밖)

- 주문 상세 조회 / 영수증 응답 등 사용처가 생기면 별도 PR 로 응답 DTO 에 `unitPrice` 를 노출한다. 본 task adr 결정 3 의 근거를 참조한다.
