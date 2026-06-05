# Order Item Price Snapshot Retrospective

## 개요

본 트랙은 ADR-020 후속 series (Stock #199 / Order #200 / Payment #202 / FK cleanup #203) 가 완전히 종료된 뒤 별도 후속 트랙으로 분리된 결제 시점 가격 snapshot 작업이다. series 메타 원칙이 "schema 변경 0건" 이었기 때문에 `OrderItem` 단가 컬럼 신설을 의도적으로 보류했고, series 종료 후 Issue #201 로 별도 트랙을 편성했다.

변경 면적은 작다. `OrderItem.java` 에 `unitPrice` 필드와 `of(...)` 팩토리 메서드 파라미터 추가, `Order.addOrderItem` 내부 호출 1줄 수정, Flyway V5 migration 1개, 단위 / 슬라이스 테스트 assertion 보강이 전부다. 루트 docs 갱신 (`docs/adr.md` 색인 표 1행, `docs/db-schema.md` 컬럼 비고) 은 별도 step으로 분리했다.

본 PR 머지 시점에 `OrderItem.unitPrice` 컬럼이 entity / schema 양쪽에 존재하게 됐다. `Product.price` 가 추후 변동되어도 기존 `OrderItem.unitPrice` 는 결제 시점 가격을 그대로 유지한다.

---

## 결정 흐름

### 1. 타입은 `int` 로 채택하고 Money VO 도입은 보류한다

`int` 와 `Money` VO 두 안을 비교했다.

기존 코드베이스의 `Order.totalPrice`, `Payment.amount`, `Product.price` 가 모두 `int` 다. `Money` VO 를 도입하면 이 세 필드도 함께 전환해야 응집력이 생기는데, 그렇게 되면 Order / Payment 도메인 전반과 외부 시그니처 (`addOrderItem`, `createCompleted` 등) 에 영향이 번진다. 사실상 별도 series 규모의 작업이다.

본 task 의 핵심 목적은 "결제 시점 가격을 entity 에 보존하는 것" 이다. `int` 통일로 그 목적을 달성할 수 있으므로 Money VO 도입은 별도 트랙으로 남겼다.

### 2. 기존 row backfill 은 `tbl_product.price` JOIN 으로 채운다

`unit_price` 컬럼을 NOT NULL 로 두려면 기존 row 에 값을 채워야 한다. 선택지는 세 가지였다.

- **(A) `0` 으로 채운다**: 간단하지만, 향후 응답 DTO 에 `unitPrice` 가 노출됐을 때 기존 주문의 단가가 0 원으로 보여 큰 오해를 부른다.
- **(B) `tbl_product.price` JOIN 으로 채운다**: 결제 시점 가격이 아닌 "migration 적용 시점의 product 현재가" 라는 부정확성이 있지만, 사용처가 없는 지금은 통계 / 영수증보다 "그럴듯한 추정값" 이 낫다.
- **(C) NULL 허용 유지**: snapshot 정책이 무력화된다. NOT NULL 제약이 "snapshot 보존" 이라는 도메인 invariant 를 표현하므로 이 안은 설계 의도와 맞지 않는다.

(B) 를 선택했다. 다만 PR review 단계에서 `product_id` FK 가 V4 에서 제거된 상태라 product hard-delete 가능성을 schema 차원에서 막아주지 않는다는 지적이 있었고, INNER JOIN backfill 은 NULL 잔여로 migration 이 실패할 수 있다는 점이 드러났다. LEFT JOIN + `COALESCE(p.price, 0)` 로 fallback 하여 운영 안정성을 확보하는 방향으로 보강했다. `0` 으로 채워진 row 는 "product 부재" 의 sentinel 로 후속 사용처에서 이상치로 잡힌다. backfill 정확도의 한계와 0 fallback 의미는 task adr 결정 2 에 명문화해 두었다.

### 3. 응답 DTO 노출은 본 PR 범위 밖이다

이슈 #201 작업 범위에 "결제 응답 / 영수청 등 `unitPrice` 노출이 필요한 응답 DTO 가 있다면 함께 정비" 가 적혀 있었다. 현재 코드베이스를 확인한 결과 `OrderItem` 을 직접 노출하는 응답 DTO 가 없었다. `OrderCreateResult` 는 `orderId / totalPrice / status` 만, `OrderCancelResult` 는 `orderId / status` 만, `PaymentReadyService` 는 `order.getTotalPrice()` 만 사용한다.

사용처 없는 상태에서 DTO 를 미리 추가하면 사용처 없는 필드가 늘어난다. 실제 사용처 (주문 상세 조회, 영수증 응답 등) 가 생기는 시점에 별도 PR 로 추가한다.

### 4. 본문 ADR 을 신규 작성하지 않고 task adr + 색인 표 한 줄로 관리한다

`docs/adr.md` 에 ADR-026 같은 본문 ADR 을 신규 작성하는 안도 검토했다. 그러나 `docs/adr.md` 상단 정책이 "코드베이스 전반에 영향을 주는 cross-cutting 결정은 본 adr.md 본문에, 특정 도메인 한정 결정은 task adr 에 둔다" 라고 명시한다.

`OrderItem.unitPrice` 신설은 Order 도메인 한정 결정이다. task adr 3개 결정으로 분리했고, `docs/adr.md` Task ADR 색인 표에 1행을 추가하는 것으로 충분하다.

---

## 기각된 옵션

| 옵션 | 검토 이유 | 기각 사유 |
|---|---|---|
| `unit_price` backfill 을 0 으로 채운다 | 단순한 구현 | 향후 응답 DTO 에 노출 시 기존 주문 단가가 0 원으로 표시되어 오해를 부름 |
| `unit_price` NULL 허용 유지 | NOT NULL 제약 추가 없이 schema 단순화 | "snapshot 보존" invariant 표현이 무력화됨. NOT NULL 이 도메인 결정을 schema 로 표현하는 유일한 수단 |
| Money VO 도입 | 타입 정확성 향상 | `Order.totalPrice`, `Payment.amount` 까지 영향이 번져 별도 series 규모가 됨. 본 task 는 `int` 통일로 목적 달성 가능 |
| 응답 DTO (`OrderCreateResult` 등) 에 `unitPrice` 노출 | 이슈 #201 원래 범위 | 현재 사용처 없음. 사용처 없는 필드 선제 추가는 피한다 |
| `docs/adr.md` 에 ADR-026 본문 ADR 신규 작성 | 결정 가시성 확보 | Order 도메인 한정 결정이라 task adr 범주. docs/adr.md 상단 정책 준수 |
| `order-jpa-association-decouple` task 폴더 회고 보강 | series 추적 가시성 | 완료된 tasks 불변 원칙 위반. series 연계 사실은 본 회고와 루트 docs 에서만 표현 |

---

## series baseline 과의 관계

### ADR-020 series 이후 처음 schema 를 변경한 후속 트랙

ADR-020 series (Stock #199 / Order #200 / Payment #202 / FK cleanup #203) 의 메타 원칙은 "schema 변경 0건" 이었다. 본 트랙은 그 series 가 완전히 종료된 이후에 등장한 첫 번째 schema 변경이다. Order sub-PR (#200) 의 ADR 결정 3 이 "결제 시점 가격 snapshot 은 후속 정비 항목" 이라고 명시했고, `docs/tasks/order-jpa-association-decouple/retrospective.md` 의 "별도 트랙으로 분리된 항목들" 섹션에서도 Issue #201 로 추적 가능하다.

series 의 schema 무변경 원칙은 "코드 변경의 영향 범위를 좁히는 설계 선택" 이었고, 그 결과로 `OrderItem.java` 의 미해결 주석 (`// 가격도 넣어야 하나?`) 이 PR #200 이후에도 코드에 남아있는 lag 가 발생했다. 본 트랙이 그 lag 를 해소했다.

### FK cleanup series 종료 직후 편성된 이유

FK cleanup (#203) 머지 시점에 ADR-020 series 가 완전히 종료됐고, series 전반에서 보류해 온 "schema 변경" 항목을 처리할 수 있는 상태가 됐다. 본 트랙은 그 흐름의 첫 번째 after-series 작업이다. 의미 있는 timing 이다.

---

## 사실 기록 (lag)

### `OrderItem.java` 미해결 주석이 PR #200 이후에도 잔류했다

PR #200 (`refactor: Order·OrderItem JPA 연관관계 분리`) 에서 `Order.addOrderItem` 시그니처가 `addOrderItem(Long productId, int quantity, int unitPrice)` 로 전환됐다. 이때 `unitPrice` 인자가 도메인에 흘러 들어왔지만 `OrderItem` 에는 저장되지 않았고, `OrderItem.java` 의 `// 가격도 넣어야 하나?`, `// 추후 고려하기` 두 줄의 미해결 주석은 해소되지 않은 채 머지됐다.

series 메타 원칙 ("schema 변경 0건") 상 불가피한 선택이었지만, 결과적으로 코드에 "미결 결정" 의 흔적이 남아있는 기간이 생겼다. 본 task 에서 결정을 명문화하고 주석을 제거함으로써 해소됐다.

### 기존 row 의 `unit_price` 가 "결제 시점 가격" 이 아니다

V5 backfill 이후 기존 `OrderItem` row 의 `unit_price` 는 "migration 적용 시점의 `tbl_product.price`" 로 채워진다. 결제가 발생했던 시점의 가격이 아니다. 이는 결제 시점 가격이 애초에 entity 에 저장되지 않았기 때문에 발생한 구조적 한계다.

신규 row 부터는 `addOrderItem(productId, qty, product.getPrice())` 호출 시점의 가격이 정확히 보존된다. 기존 row 의 `unit_price` 는 "migration 적용 시점 현재가" 라는 점을 사용처가 생기면 인지하고 있어야 한다. 이 한계는 task adr 결정 2 에 명문화되어 있다.

---

## 아쉬운 점

### 응답 DTO 노출까지 한 번에 가지 못했다

entity / schema 차원의 snapshot 보존과 응답 DTO 노출을 분리한 것은 "사용처 없는 상태에서 선제 추가 금지" 원칙에 따른 결정이었다. 그러나 큰 그림에서 보면 "단가 컬럼은 DB 에 있는데 응답에는 없다" 는 partial 변경 상태가 만들어졌다. 향후 응답 DTO 를 추가할 때 이 분리된 히스토리를 추적해야 한다는 탐색 비용이 생겼다.

기능의 완결성 관점에서 "unitPrice 를 저장한다" 와 "unitPrice 를 응답에 노출한다" 는 다른 결정이지만, 결국 같은 기능의 두 반쪽이다. 사용처가 분명히 보이는 시점 (예: 주문 상세 조회 API 기획이 확정된 시점) 에 entity / migration / 응답 DTO 를 한 PR 에서 완결하는 것이 더 깔끔했을 수 있다.

### Money VO 도입을 다시 미뤘다

`Order.totalPrice` / `Payment.amount` / `Product.price` / `OrderItem.unitPrice` 가 모두 `int` 다. 가격 도메인 개념이 원시 타입으로 흩어진 상태에서 연산 (할인 / 정산 / 환불) 이 추가되면 부채가 가시화될 가능성이 있다. 결제 / 정산 도메인 전반의 가격 표현 통일은 현재 코드베이스의 규모상 단순히 "필드를 교체하는 것" 이 아니라 도메인 재설계에 가까운 작업이 될 것이다.

---

## 후속 작업

- **주문 상세 조회 / 영수청 응답에 `unitPrice` 노출**: 사용처가 생기는 시점에 별도 PR 로 `OrderItem.unitPrice` 를 응답 DTO 에 추가한다. 이때 기존 row 의 `unit_price` 가 "migration 적용 시점 현재가" 라는 한계를 함께 문서화한다.
- **가격 정책 변경 / Money VO 도입 검토**: `int` 기반 가격 표현의 한계가 가시화되는 시점에 별도 series 를 편성한다. `Order.totalPrice`, `Payment.amount`, `Product.price`, `OrderItem.unitPrice` 를 한 번에 전환하는 것이 적합하다.
