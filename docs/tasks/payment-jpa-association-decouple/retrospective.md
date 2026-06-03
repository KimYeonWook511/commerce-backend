# Payment JPA Association Decouple Retrospective

## 개요

본 sub-PR은 ADR-020 후속 트랙의 세 번째이자 마지막 작업이다. `Stock.product(@OneToOne)` / `StockHistory.stock(@ManyToOne)` 을 해제한 선행 stock sub-PR의 메타 원칙과, Order의 fetch join 대체 일반 원칙 / Long ID 시그니처 패턴을 확립한 선행 order sub-PR의 결정을 그대로 계승하며, Payment 도메인의 유일한 cross-aggregate association(`Payment.order @OneToOne Order`)을 `Long orderId`로 전환했다.

Payment 도메인의 변경 면적은 선행 두 sub-PR 대비 눈에 띄게 좁다. PaymentAttempt는 이미 식별자(`merchantPayKey`, `pgPaymentId`) 기반이었고, `JpaPaymentRepository`에 fetch join JPQL이 없었으며, Payment 응답이 외부 컨텍스트 주입을 필요로 하지 않았다. 결정해야 할 사항이 `Payment.createCompleted` 시그니처 전환 1건으로 수렴했고, 나머지는 선행 sub-PR의 결정을 인용하는 것으로 충분했다.

본 PR 머지로 ADR-020 후속 트랙의 기존 도메인 마이그레이션(Stock / Order / Payment)이 모두 완료됐다. Issue #195는 본 PR 머지 후 close되며, DB FK 일괄 제거(`fk_stock_product_id`, `fk_stock_history_stock_id`, `fk_order_member_id`, `fk_order_item_product_id`, `fk_payment_order_id`) 는 별도 issue / PR로 발행된다.

---

## 결정 흐름

### 1. Payment 도메인의 변경 면적 파악 — 왜 선행 sub-PR보다 좁은가

Stock sub-PR은 fetch join 대체 패턴을 Order로 위임했고, Order sub-PR은 네 가지 fetch join 사용처를 분석하며 일반 원칙을 새로 정립하는 데 상당한 결정 비용이 들었다. Payment에서는 동일한 분석 과정이 필요하지 않았다.

이유는 셋이다. 첫째, `JpaPaymentRepository`에 JPQL 자체가 없다. derived query(`findByMerchantPayKey`, `existsByMerchantPayKeyAndStatus`)만 존재한다. fetch join 대체 패턴이 필요한 사용처가 0건이므로 Order sub-PR 결정 2의 일반 원칙을 인용만 하면 된다. 둘째, `PaymentAttempt`는 `merchantPayKey` / `pgPaymentId` / `provider` 식별자 기반이어서 JPA association 자체가 없다. ADR-020이 지적한 통증 패턴(편한 탐색 오용, 이중 표현, lazy proxy 함정, fixture 부담, FK 정책 강제)이 발생하지 않는 상태다. 셋째, Payment 응답(`NaverPayApproveResponse`)은 Payment 자신의 필드(`pgPaymentId`, `status`)만으로 조립 가능하다. Order sub-PR에서 도입한 "외부 컨텍스트 주입" 패턴을 새로 확장할 사용처가 없다.

결국 본 sub-PR의 핵심 결정은 `Payment.createCompleted` 시그니처 전환 1건으로 좁혀졌다.

### 2. `Payment.createCompleted` 시그니처 — Long ID + amount 명시 vs Order 객체 유지

`@OneToOne Order` association을 제거하면 도메인에 Order 객체를 넘기는 명목적 이유가 사라진다. 두 가지 옵션을 검토했다.

**A안(시그니처 유지)**: application이 Order를 `findByMerchantPayKeyForUpdate`로 로드한 뒤 객체째 도메인에 넘기는 구조다. JPA association 해제 후 도메인이 Order 객체를 받아서 할 수 있는 일이 `getTotalPrice()` 호출 1건뿐이다. 외부 객체 의존을 시그니처에서 제거하려는 Long ID 전환의 정신을 절반만 달성하는 셈이다.

**B안(Long ID 시그니처)**: `Payment.createCompleted(Long orderId, int amount, ...)` 로 전환하고, application 이 `order.getId()` / `order.getTotalPrice()` 를 추출해 전달하도록 변경했다. Order PR #200 결정 3의 Long ID 시그니처 패턴을 그대로 적용했다.

B안을 선택한 이유 두 가지: 첫째, 호출처(`PaymentApprovalService.completeApprovedPayment`)가 같은 트랜잭션에서 `order.completePayment()`를 호출하고 `order.getTotalPrice()`를 읽으므로 Order 객체 로드가 어차피 필요하다. 추가 조회 비용이 0이다. 둘째, `amount` 인자를 호출자가 명시적으로 전달함으로써 "Order의 `totalPrice`를 결제 시점 `amount`로 쓴다"는 정책이 application 코드 표면에 드러난다. 이전에는 `Payment.createCompleted(order, ...)` 내부에서 `order.getTotalPrice()`가 호출됐기 때문에 이 정책이 도메인 안에 숨어 있었다.

### 3. `OrderRepository.existsById` 신설 여부 — Order PR #200 의 회수된 시도 학습 계승

Order sub-PR 과정에서 product 존재 검증을 위해 `ProductRepository.existsById` 류 신설을 검토했다가 "호출처가 같은 트랜잭션에서 객체 필드를 함께 쓰므로 신설 메서드의 사용처가 0건"이라는 이유로 회수한 학습이 있다.

Payment의 경우도 동일하다. `completeApprovedPayment`는 Order를 `findByMerchantPayKeyForUpdate`로 로드한 뒤 `order.completePayment()` / `order.getTotalPrice()` / `order.getId()`를 같은 트랜잭션에서 함께 쓴다. Order 존재 여부를 확인하는 별도 메서드를 `OrderRepository`에 추가할 이유가 없다. 기존 `findByMerchantPayKeyForUpdate`가 없으면 `ORDER_NOT_FOUND` 예외를 던지므로 존재 검증은 이미 포함되어 있다.

### 4. PaymentAttempt를 본 sub-PR 범위 밖으로 둔 이유

Payment와 PaymentAttempt가 같은 aggregate인지, 또는 cross-aggregate 경계를 명시해야 하는지 판단이 필요했다. 그러나 본 sub-PR의 정책 목적은 "JPA cross-aggregate association 해제"이며, PaymentAttempt는 이미 해제된 상태다. `Payment.@OneToMany PaymentAttempt`나 `PaymentAttempt.@ManyToOne Payment` 같은 JPA association이 없다. 둘 다 `merchantPayKey`를 공유하는 식별자 기반 설계다.

ADR-020이 지적하는 통증(편한 탐색 오용, lazy proxy 함정 등)이 PaymentAttempt에서 발생하지 않는 상태에서 aggregate 경계를 새로 명시하거나 PaymentAttempt 영속 패턴을 정비하는 것은 본 sub-PR의 정책 범위 밖이다. 그 결정은 별도 트랙으로 분리됐다.

### 5. 응답 echo 정리를 본 sub-PR에 섞지 않은 이유

선행 stock sub-PR 결정 2와 Order sub-PR 결정 4가 "응답 echo 정리는 별도 트랙"이라는 정책을 연달아 확립했다. 본 sub-PR도 동일한 정책을 계승했다.

본 sub-PR의 정책 목적은 cross-aggregate ID 참조 통일과 series 마무리이지, 응답 필드 구성 정비가 아니다. `NaverPayApproveResponse`의 `pgPaymentId` echo 구조 등을 이번에 정비했다면 "association 해제"와 "응답 계약 정비"가 같은 diff에 묶여 PR 메시지가 흐려졌을 것이다.

---

## 기각된 옵션

| 옵션 | 검토 이유 | 기각 사유 |
|---|---|---|
| `Payment.createCompleted` 시그니처 유지 (Order 객체 그대로) | 변경 최소화 | association 해제 후 도메인에 객체 넘기는 명목 사라짐. `getTotalPrice()` 1건만 쓰는 구조에서 외부 객체 의존 잔존 |
| `OrderRepository.existsById` 신설 | application 레이어의 명시적 존재 검증 | 호출처가 같은 트랜잭션에서 Order 객체의 다른 필드를 함께 쓰므로 신설 메서드 사용처 0건. Order PR #200 의 회수된 시도와 동일한 판단 |
| PaymentAttempt 포함 정비 | series 일관성 확장 | JPA association 자체가 없어 해제 대상이 없음. aggregate 경계 명시는 별도 정비 트랙 |
| 응답 echo 정리 포함 | 한 PR에서 완결 | 본 sub-PR의 정책 목적과 다른 축. 선행 두 sub-PR 동일 정책 계승 |
| fetch join 대체 패턴 신규 ADR 작성 | 일관성 문서화 | Payment 도메인에 fetch join 사용처 0건. Order sub-PR 일반 원칙 인용으로 충분 |

---

## series 전체 baseline 정리

### 메타 원칙 — 세 sub-PR 전체에서 유지됨

- **schema 변경 0건**: Flyway V 파일 추가 없음. 컬럼·FK·unique 제약 그대로 유지. 세 sub-PR 모두 동일 원칙으로 진행됐고 Hibernate `validate` 통과를 integrationTest(Testcontainers MySQL)로 매번 확인했다.
- **FK 제약 유지**: `fk_stock_product_id`, `fk_stock_history_stock_id`, `fk_order_member_id`, `fk_order_item_product_id`, `fk_payment_order_id` 다섯 FK가 DB schema에 남아있다. JPA가 인식하지 않을 뿐이며 referential integrity는 DB 차원에서 유지된다. 본 series 완료 후 별도 issue/PR에서 일괄 제거한다.
- **응답 계약 무변경**: 세 sub-PR 모두 응답 DTO 시그니처·필드를 건드리지 않았다. "응답 echo 정리는 별도 트랙"이라는 정책이 series 전체를 관통한다.

### 도메인 시그니처 Long ID 패턴 — Order PR에서 정립, Payment PR에서 계승

Order sub-PR이 `Order.create(Long memberId)` / `addOrderItem(Long productId, int, int)` 전환으로 Long ID 시그니처 패턴을 처음 정립했다. 본 Payment sub-PR이 `Payment.createCompleted(Long orderId, int amount, ...)` 전환으로 동일 패턴을 계승했다. 핵심 원칙은 "도메인은 ID + 단순 값만 받고, 외부 객체 의존 없이 동작한다"이다. cart나 이후 도메인 팩토리 메서드를 설계할 때 이 패턴을 참조할 수 있다.

### fetch join 대체 일반 원칙 — Order PR에서 정립, Payment PR에서 인용만

Order sub-PR이 네 가지 사용처 분석을 통해 일반 원칙("same-aggregate fetch 유지 / cross-aggregate fetch 제거, 데이터 양상별 batch composition 또는 컬럼 직접 사용")을 처음 명문화했다. Payment 도메인은 fetch join JPQL이 없어 본 PR에서 신규 결정 사항이 없었다. 이 일반 원칙은 `docs/tasks/order-jpa-association-decouple/adr.md` 결정 2에서 단일 관리된다.

### 응답 DTO 외부 주입 패턴 — Stock PR에서 시작, Order PR에서 확장, Payment PR에서 비적용

Stock sub-PR의 `StockHistoryResult.from(history, productId)` 패턴이 외부 컨텍스트 주입의 최초 사례였다. Order sub-PR이 `PaymentReadyResult.from(order, productNameByProductId)` 로 자연스럽게 확장했다. Payment sub-PR에서는 Payment 응답이 이미 Payment 자신의 필드로 완결되어 신규 적용 사례가 없었다. 이 패턴은 "응답 DTO가 여러 aggregate의 데이터를 조립해야 할 때 entity 객체 traversal 대신 application 계층이 명시적으로 조립한다"는 원칙으로 남는다.

### PaymentAttempt 같은 식별자 기반 entity — 본 series의 정책 목적과 무관

`PaymentAttempt`처럼 이미 식별자 기반으로 설계된 entity는 본 series의 "JPA cross-aggregate association 해제" 정책 목적과 처음부터 무관하다. aggregate 경계 명시나 영속 패턴 정비가 필요하다면 별도 트랙에서 다룬다.

---

## 운영 점검

**DB FK가 schema에 남아있는 상태**: `fk_payment_order_id` FK 제약이 schema에 그대로 남아있고 JPA가 더 이상 인식하지 않는다. DB 차원의 referential integrity는 유지된다. Order가 없는 `order_id` 값으로 Payment 저장 시도 시 FK violation이 발생하며 기존과 동일하게 GlobalExceptionHandler 500 안전망으로 위임된다.

본 PR 머지로 Stock / Order / Payment 세 sub-PR이 모두 완료된다. Issue #195를 close하고, `fk_stock_product_id` / `fk_stock_history_stock_id` / `fk_order_member_id` / `fk_order_item_product_id` / `fk_payment_order_id` 다섯 FK를 한 Flyway migration으로 일괄 제거하는 별도 issue를 발행한다.

**Payment 도메인의 회귀 위험**: 변경 면적이 `Payment` entity 1개 + `PaymentApprovalService.completeApprovedPayment` 1개 메서드 + test fixture 5개 파일로 좁다. 영향이 큰 흐름은 결제 승인 완료 흐름(`NaverPayApprovalService → PaymentApprovalService.completeApprovedPayment`)이며, Order 조회·잠금·`completePayment()` 호출·Payment 저장 순서는 기존과 동일하다. 보상 흐름(`PaymentApprovalCompensationService`)은 변경 없이 `PaymentAttempt.merchantPayKey` / `pgPaymentId` 기반으로 그대로 동작한다. concurrency 태그 테스트도 fixture 갱신만으로 회귀 없음을 확인했다.

---

## 자기 평가

### 잘된 점

- **Payment 도메인 특유의 좁은 변경 면적을 사전에 정확히 파악**: PaymentAttempt 식별자 기반 설계 / fetch join 0건 / 응답 외부 주입 불필요라는 세 사실을 분석해 결정 사항을 최소화했다. 선행 두 sub-PR 대비 ADR 결정 6건 중 3건이 "본 sub-PR에서 새 결정 없음, 선행 원칙 인용"으로 채워졌다.
- **선행 두 sub-PR의 패턴을 일관되게 인용해 series 일관성 유지**: Long ID 시그니처 패턴 / fetch join 대체 일반 원칙 / 응답 echo 정리 별도 트랙이 모두 선행 sub-PR의 결정 포인트를 그대로 따랐다. series 내에서 동일한 정책이 반복 적용됐음이 ADR에서 명확히 추적된다.
- **`amount` 명시 인자의 부가 효과**: `Payment.createCompleted(Long orderId, int amount, ...)` 전환으로 "Order의 `totalPrice`를 결제 시점 `amount`로 쓴다"는 정책이 application 코드 표면에 드러났다. 이전에는 이 정책이 도메인 내부의 `order.getTotalPrice()` 호출에 숨어 있었다.
- **Hibernate validate 검증**: Testcontainers MySQL을 기동하는 `integrationTest`를 포함해 schema와 매핑 정합성을 실행 결과로 확인했다. series 세 sub-PR 모두 동일한 검증 기준을 유지했다.

### 아쉬운 점

- **PaymentAttempt의 aggregate 경계 명시가 후속 과제로 남음**: `Payment`와 `PaymentAttempt`는 둘 다 `merchantPayKey`를 공유하는 식별자 기반 설계인데, 이 결합이 ADR에서 "별 entity이며 객체 참조가 없다"는 사실 기록으로만 표현된다. 둘의 aggregate 경계(같은 aggregate인지 별 aggregate인지)와 영속 패턴이 명시적으로 논의된 적 없다. 본 series 범위 밖으로 미루었으나 후속 정비 트랙이 아직 명확하지 않다.
- **`Payment` ↔ `PaymentAttempt` 의 식별자 기반 결합의 domain explicitness**: `merchantPayKey`가 Payment와 PaymentAttempt를 연결하는 유일한 링크인데, 이것이 도메인 레이어에서 명시적으로 표현된 개념이 아니다. 현재는 application 계층에서 `merchantPayKey`를 키로 두 entity를 함께 다루는 방식으로만 표현된다. 장기적으로 도메인 명시성 차원에서 후속 정비 여지가 있다.
- **결제 시점 가격 snapshot 미해결**: Order sub-PR에서 `addOrderItem`의 `unitPrice` 인자가 `OrderItem` 컬럼에 저장되지 않는 문제가 미해결로 남았다. 본 Payment sub-PR에서 `amount = order.getTotalPrice()`를 명시적으로 전달하게 됐지만, 그 `totalPrice`가 OrderItem 단가의 누적인지 결제 시점 스냅샷인지는 여전히 불분명하다. Issue #201 별도 트랙에서 다룬다.
