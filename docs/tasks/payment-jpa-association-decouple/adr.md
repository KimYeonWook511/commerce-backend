# 태스크 ADR

## 결정 1: Payment 의 JPA cross-aggregate association 을 해제하고 Long ID 로 전환한다 (ADR-020 후속 트랙 마무리)

### 배경

- ADR-020 은 신규 도메인 (cart) 부터 cross-aggregate 를 `Long` ID 로 참조한다고 결정했으나, 기존 도메인은 호환성 부담으로 별도 트랙으로 분리됐다.
- Issue #195 가 별도 트랙을 도메인 경계 단위 sub-PR 로 진행 중. 본 태스크는 선행 `stock-jpa-association-decouple` (#199), `order-jpa-association-decouple` (#200) 에 이은 **세 번째이자 마지막 sub-PR**.
- 진행 단위는 선행 sub-PR 의 결정 1 (도메인 경계 단위 sub-PR 분리) 을 그대로 따른다.

### 결정 내용

- Payment aggregate 의 cross-aggregate association 만 해제한다.
  - `Payment.order` (`@OneToOne(LAZY) Order`) → `Long orderId`.
- Payment 와 PaymentAttempt 는 별 entity 로 두며 객체 참조가 없다 — 본 sub-PR 에서 PaymentAttempt 변경 없음.
- Order 와 Payment 는 별 aggregate 로 다룬다 (cascade / orphanRemoval 없음, lifecycle 결합 약함).

### 근거

- ADR-020 의 cross-aggregate ID 참조 원칙을 Payment 도메인에 동일하게 적용한다.
- Payment ↔ Order 는 `@OneToOne(LAZY)` 단방향 참조이며 cascade 가 없다. Order 가 만료/취소되어도 Payment 이력은 남아야 하고, Payment 가 취소되어도 Order 이력은 남는다. lifecycle 결합이 약하므로 별 aggregate 가 자연스럽다. ADR-020 의 "같은 aggregate 내 root-child 객체 참조 허용" 예외에 해당하지 않는다.
- PaymentAttempt 는 이미 `merchantPayKey` / `pgPaymentId` / `provider` 식별자 기반이며 다른 entity 와 객체 참조가 없다. ADR-020 의 통증 (편한 탐색 오용 / 이중 표현 / fixture 부담) 이 이미 발생하지 않는 상태.

### 결과

- Payment aggregate 가 외부 aggregate (Order) 를 ID 로만 참조.
- Payment ↔ PaymentAttempt 는 기존과 동일하게 별 entity 로 유지 — 객체 참조 추가 없음.
- Issue #195 의 모든 sub-PR (Stock / Order / Payment) 머지 완료. #195 close.
- DB FK 일괄 제거는 별도 트랙 (별도 issue / PR) — Stock / Order / Payment 의 모든 FK 제약을 한 Flyway migration 으로 일괄 정리.

## 결정 2: Payment.createCompleted 시그니처를 Long ID 로 전환한다 (Order PR #200 결정 3 패턴 인용)

### 배경

- 현재 `Payment.createCompleted(Order order, PaymentProvider provider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)` 는 Order 객체를 받아 내부에서 `order.getTotalPrice()` 만 호출한다.
- JPA association 해제 후 도메인에 Order 객체를 넘기는 명목적 이유가 사라진다. 시그니처를 재정비할 자연스러운 시점.
- 옵션:
  - (A) 시그니처 유지 — application 에서 Order 를 계속 받아 도메인에 넘긴다.
  - (B) Long ID 시그니처 — `Payment.createCompleted(Long orderId, int amount, ...)`. application 이 Order 를 조회 후 `getId()`, `getTotalPrice()` 추출해 전달.

### 결정 내용

- (B) 를 채택한다.
- `Payment.createCompleted(Long orderId, int amount, PaymentProvider provider, String merchantPayKey, String pgPaymentId, LocalDateTime approvedAt)` 로 시그니처 전환.
- application (`PaymentApprovalService.completeApprovedPayment`) 의 Order 조회 흐름은 그대로 유지한다 (`orderRepository.findByMerchantPayKeyForUpdate(...)`). 호출처가 같은 트랜잭션에서 `order.completePayment()` 를 호출하고 `order.getId()`, `order.getTotalPrice()` 를 추출하므로 Order 객체 로드가 어차피 필요하다.

### 근거

- Order PR #200 결정 3 의 Long ID 시그니처 패턴을 그대로 적용한다 — 도메인 invariant 가 ID 기준으로 명확해진다.
- 본 코드베이스의 회수된 시도 (Order PR 의 신설 `existsById`) 학습을 따른다 — 신설 검증 API 는 사용처 0건이면 추가하지 않는다. `OrderRepository` 에 신규 메서드 추가 없음.
- 도메인이 외부 객체 의존 없이 ID + 단순 값 (`int amount`) 만 받게 되어 unit test fixture 부담이 줄어든다.
- `amount` 를 호출처가 명시적으로 전달함으로써 "Order 의 totalPrice 를 결제 시점 amount 로 쓴다" 는 정책이 application 코드 표면에 드러난다 (도메인 안에 숨지 않음).

### 결과

- domain layer 의 외부 객체 의존 0건.
- application 의 Order 조회 패턴은 기존 그대로 유지 (`findByMerchantPayKeyForUpdate`).
- `OrderRepository` 인터페이스에 신규 메서드 추가 없음.

## 결정 3: fetch join 대체 정책은 본 sub-PR 에서 새로 결정하지 않는다 (Order PR #200 일반 원칙 인용)

### 배경

- 선행 Order sub-PR (#200) 의 결정 2 가 fetch join 대체 일반 원칙을 정립했다 — "same-aggregate fetch 유지 / cross-aggregate fetch 제거, 데이터 양상별로 batch composition 또는 컬럼 직접 사용 분기".
- Payment 도메인은 `JpaPaymentRepository` 에 fetch join JPQL 이 없다. derived query (`findByMerchantPayKey`, `existsByMerchantPayKeyAndStatus`) 만 사용.

### 결정 내용

- 본 sub-PR 에서 fetch join 대체 정책 결정 사항 없음.
- Order PR #200 결정 2 의 일반 원칙을 인용만 한다.

### 근거

- Payment 도메인의 모든 데이터 접근이 `merchantPayKey` 단일 키 기반 derived query 로 단순하다.
- 일반 원칙 사전 선언이 본 sub-PR 의 작업 단위와 무관하다 (선행 sub-PR 의 결정 4 와 동일한 정신).

### 결과

- 본 ADR 에는 fetch join 대체 정책이 포함되지 않는다.
- 일반 원칙은 `docs/tasks/order-jpa-association-decouple/adr.md` 결정 2 에서 단일 관리.

## 결정 4: DB schema 변경 / Flyway migration 없이 진행한다 (메타 원칙 재확인)

### 배경

- 선행 stock sub-PR (#199) 결정 3 으로 이미 정립된 series 의 메타 원칙: "코드 차원 association 해제만, schema 변경 0건".
- Payment 도메인의 컬럼 (`order_id BIGINT NOT NULL`) 은 이미 존재한다. JPA 매핑만 association 해제하면 schema 변경 없이 완결 가능.
- DB FK 제약 (`fk_payment_order_id`) 과 unique 제약 (`uk_payment_order_id`) 은 schema 에 남아있고 JPA 가 더 이상 FK 정보를 인식하지 않을 뿐이다 — unique 제약은 `@Table(uniqueConstraints = ...)` 매핑으로 유지된다.

### 결정 내용

- FK 제약 유지, Flyway migration 없이 진행한다.
- DB FK 일괄 제거는 Issue #195 의 모든 sub-PR 완료 후 별도 트랙에서 진행한다 (본 sub-PR 머지로 series 완료).

### 근거

- series 메타 원칙 (`docs/tasks/stock-jpa-association-decouple/adr.md` 결정 3) 을 그대로 따른다.
- Issue #195 본문 "DB FK 제약조건 일괄 제거 — 모든 코드 마이그레이션 완료 후 별도 PR/Issue 에서 진행" 명시.
- Hibernate `validate` 는 컬럼 단위 검증이고 FK 제약 존재 여부는 검증 대상이 아니다. `@OneToOne` 제거 후에도 `@Column(name = "order_id", nullable = false)` 매핑은 유지되므로 validate 통과 가능 (선행 stock / order sub-PR 에서 동일 패턴 검증됨).

### 결과

- 본 sub-PR 의 변경은 코드 / JPA 매핑에 한정.
- 본 sub-PR 머지로 series 완료 — 후속 DB FK 제거 트랙 신규 issue 발행 예고.

## 결정 5: 응답 echo 정리는 본 sub-PR 의 범위가 아니다 (선행 sub-PR 정책 계승)

### 배경

- 선행 stock sub-PR (#199) 결정 2, Order sub-PR (#200) 결정 5 가 응답 echo 정리를 별도 트랙으로 분리했다.
- Payment 응답 (`NaverPayApproveResponse`, `PaymentReadyResult`) 도 유사한 echo 구조 (`pgPaymentId` 등) 가 있을 수 있다.

### 결정 내용

- 본 sub-PR 에서는 응답 필드 구성을 변경하지 않는다.
- 본 sub-PR 의 변경은 domain layer 와 1개의 application 메서드 호출부 한정. 응답 DTO 시그니처·필드 변경 없음.
- 응답 echo 정리는 별도 트랙.

### 근거

- 본 sub-PR 의 정책 목적은 cross-aggregate ID 참조 통일과 series 마무리이지 응답 계약 정비가 아니다.
- 두 가지를 한 PR 에 묶으면 PR 메시지가 흐려진다 (`docs/commit-conventions.md` "역할이 다른 변경을 이유 없이 하나로 묶지 않는다").
- 선행 두 sub-PR 의 결정과 일관.

### 결과

- API 응답 계약 유지. frontend 영향 없음.
- 응답 echo 정리는 후속 별도 트랙으로 분리.

## 결정 6: PaymentAttempt 는 본 sub-PR 영향 범위 밖이다

### 배경

- 선행 stock sub-PR 의 결정 1 이 Stock 과 StockHistory 의 aggregate 경계 (별 aggregate / cross-aggregate 해제) 를 명시했다.
- Payment 도메인에서도 Payment 와 PaymentAttempt 의 aggregate 경계 판단이 필요할 수 있다.

### 결정 내용

- 본 sub-PR 에서 PaymentAttempt 관련 변경 없음.
- PaymentAttempt 는 이미 `merchantPayKey` / `pgPaymentId` / `provider` 식별자 기반이며 Payment 와의 객체 참조가 없다.
- Payment ↔ PaymentAttempt 의 aggregate 경계는 본 sub-PR 의 정책 목적 (cross-aggregate JPA association 해제) 과 무관 — 양 entity 모두 cross-aggregate 객체 참조를 갖지 않는 상태에서 해제할 association 자체가 없다.

### 근거

- ADR-020 의 통증 (편한 탐색 오용 / 이중 표현 / lazy proxy 함정 / fixture 부담 / FK 정책 강제) 이 PaymentAttempt 에서 발생하지 않는 상태.
- 추가 작업 (예: aggregate 경계 명시 ADR, PaymentAttempt 영속 패턴 정비) 은 본 sub-PR 의 정책 목적 밖이다. 별도 트랙으로 분리.

### 결과

- PaymentAttempt 와 관련 service (`PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `PaymentApprovalCompensationService`) 는 본 sub-PR 에서 변경 없음.
- 본 sub-PR 의 변경 면적이 Payment entity / `PaymentApprovalService.completeApprovedPayment` 1건 + test fixture 로 좁게 유지된다.
