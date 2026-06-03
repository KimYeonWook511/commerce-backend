# 태스크 PRD

## 태스크명

- `payment-jpa-association-decouple`

## 배경

- ADR-020 으로 신규 도메인 (cart) 부터 cross-aggregate 를 `Long` ID 로만 참조하기로 결정했으나, 기존 도메인 (Stock / Order / Payment 등) 은 호환성 부담을 이유로 마이그레이션을 보류했다.
- Issue #195 가 보류해둔 별도 트랙을 도메인 경계 단위 sub-PR 로 점진 진행한다. 본 태스크는 series 의 **세 번째이자 마지막 sub-PR** 로 **Payment 도메인의 JPA Entity 간 cross-aggregate association 을 해제**한다.
- 선행 sub-PR:
  - `stock-jpa-association-decouple` (#199, 머지 완료) — series 메타 원칙 최초 정립.
  - `order-jpa-association-decouple` (#200, 머지 완료) — fetch join 대체 일반 원칙 / Long ID 시그니처 패턴 정립.
- 본 sub-PR 머지 후 #195 close. DB FK 일괄 제거는 별도 트랙 (신규 issue/PR).

## 목표

- Payment 도메인의 JPA `@OneToOne` cross-aggregate 객체 참조를 `Long` ID 필드로 전환한다.
- ADR-020 의 cross-aggregate ID 참조 정신을 Payment aggregate 에 적용한다.
- Payment aggregate 가 다른 aggregate (Order) 를 객체 그래프로 traverse 하지 않게 한다.
- 선행 두 sub-PR 의 메타 원칙·패턴을 그대로 따르며, Payment 도메인 특수성으로 새로 결정할 정책은 최소화한다.

## 범위

### 포함 범위

- `Payment.order` (Order 객체, `@OneToOne(LAZY)`) → `Long orderId`. JPA association 매핑 제거.
- `Payment.createCompleted(Order, ...)` 정적 팩토리를 `Payment.createCompleted(Long orderId, int amount, ...)` Long ID 시그니처로 전환. 호출처가 `order.getId()`, `order.getTotalPrice()` 를 추출해 전달.
- application 호출부 (`PaymentApprovalService.completeApprovedPayment`) 가 Long ID 인자로 도메인 호출.
- test fixture 의 `Payment.createCompleted(order, ...)`, `payment.getOrder()...` 사용처 정리.
- 루트 `docs/ADR.md`, `docs/architecture.md` 동기화 — ADR-020 후속 트랙 완료 명시.
- 회고록 작성 — series 전체 마무리 baseline 포함.

### 제외 범위

- **DB schema 변경 / Flyway migration** — 컬럼 (`order_id`) 그대로. JPA 매핑만 해제.
- **DB FK 제약 제거** — `fk_payment_order_id`, `uk_payment_order_id` 유지. series 전체 머지 후 별도 트랙에서 일괄 정리.
- **응답 API 계약 정비** — `NaverPayApproveResponse` 등 응답 DTO 의 path/command echo 정비는 별도 트랙.
- **PaymentAttempt 관련 변경** — 이미 `merchantPayKey` / `pgPaymentId` 기반이며 association 해제 대상 없음.
- **보상 흐름 / outbox / pg 연동 동작 변경** — 본 sub-PR 영향 범위 밖. 기존 동작 보존.
- **결제 시점 가격 snapshot (`OrderItem.unitPrice` 컬럼)** — Issue #201 별도 트랙.

## 주요 시나리오

- 사용자가 결제창에서 결제를 승인한다.
- PG (NaverPay) 가 승인 콜백을 보내면 `NaverPayApprovalService → PaymentApprovalService.completeApprovedPayment` 가 Order 를 조회·잠그고 Payment 를 생성한다.
- 결제 승인 멱등 재요청·중복 PG 응답·금액 불일치 등 보상 흐름이 기존과 동일하게 동작한다.
- 위 시나리오 모두 기존과 동일하게 동작하되, JPA 매핑 차원에서 `Payment.order` 객체 참조를 사용하지 않는다.

## 요구사항

- `Payment` 는 `orderId: Long` 을 가진다. `@OneToOne Order` 제거.
- `Payment.createCompleted(Long orderId, int amount, PaymentProvider provider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)` 시그니처로 정적 팩토리 정리. 도메인은 외부 객체 의존 없이 ID + 단순 값만 받는다.
- `PaymentApprovalService.completeApprovedPayment` 가 Order 조회 후 `Payment.createCompleted(order.getId(), order.getTotalPrice(), ...)` 형태로 호출한다.
- `payment.getOrder()` traversal 사용처 (main / test) 모두 제거.
- 기존 `./gradlew test integrationTest` 통과.

## 제약사항

- DB schema (테이블 / 컬럼 / FK / unique) 는 손대지 않는다. Hibernate `validate` 통과 가능해야 한다.
- API 응답 계약 유지.
- 동시성 / 보상 흐름의 회귀 없음 — `PaymentApprovalServiceConcurrencyTest`, `PaymentApprovalAttemptServiceConcurrencyTest`, `PaymentCancellationAttemptServiceConcurrencyTest`, `NaverPayServiceConcurrencyTest`, `PaymentApprovalCompensationService` 동작 보존.
- ADR-020 의 적용 범위 ("같은 aggregate 내 root-child 는 객체 참조 허용") 를 본 태스크에 그대로 따른다. Payment 와 PaymentAttempt 는 객체 참조 자체가 없어 해당 결정이 자연스럽게 충족된다.
- 본 sub-PR series 의 메타 원칙 (`docs/tasks/stock-jpa-association-decouple/adr.md` 결정 3) "코드 차원 association 해제만, schema 변경 0건" 을 유지한다.
