# 태스크 API 스펙

## 개요

- 본 태스크는 API 응답 계약을 변경하지 않는다.
- 변경 대상은 Order / OrderItem 도메인의 JPA 매핑, repository JPQL, application orchestration, test fixture 이며, controller / request / response 형식은 그대로 유지된다.

## 엔드포인트

기존 엔드포인트 그대로 유지. 본 태스크에서 형식 변경 없음.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/orders` | 주문 생성 |
| `POST` | `/payments/ready` | 결제 준비 (상품명 노출 포함) |
| `POST` | `/orders/{orderId}/cancel` | 주문 취소 |
| `GET` | `/orders/{orderId}` | 주문 조회 |

배치 / 내부 호출:

- `OrderExpirationService.expire(orderId)` — 결제 대기 만료 처리 (Spring Batch 또는 스케줄러 호출).

## 요청

기존 요청 계약 그대로 유지.

## 응답

기존 응답 계약 그대로 유지.

### 응답 조립 변경 사항 (계약 무변경, 내부 조립만 변경)

- `PaymentReadyResult` (또는 동등 응답 DTO)
  - 필드 구성 그대로 유지.
  - 내부 productName 매핑이 `orderItem.getProduct().getName()` 객체 traversal 에서 `productNameByProductId.get(orderItem.getProductId())` 외부 주입 방식으로 바뀐다.
  - 정적 팩토리 시그니처가 `from(Order order)` → `from(Order order, Map<Long, String> productNameByProductId)` 형태로 바뀐다. (실제 시그니처는 step1 구현 시 응답 DTO 의 현행 형태에 맞춰 확정한다.)
- 그 외 Order 관련 응답 DTO (취소, 만료, 조회)
  - 필드 구성 그대로 유지.
  - productId / memberId 매핑이 OrderItem 컬럼 / Order 컬럼 직접 사용으로 바뀐다.

## 검증 규칙

- 기존 검증 규칙 유지. 본 태스크에서 검증 규칙 변경 없음.

## 비고

- 응답 필드의 path/command echo (예: 응답에 productId / orderId 등이 그대로 되돌아가는 구조) 정비는 본 태스크 범위가 아니며 별도 트랙으로 분리한다.
- 선행 stock sub-PR (`stock-jpa-association-decouple`) 과 동일 정책.
