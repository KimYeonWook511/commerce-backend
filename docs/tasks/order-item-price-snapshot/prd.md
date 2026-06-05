# 태스크 PRD

## 태스크명

- `order-item-price-snapshot`

## 배경

- PR #200 (`refactor: Order·OrderItem JPA 연관관계 분리`) 에서 `Order.addOrderItem` 시그니처가 `addOrderItem(Long productId, int quantity, int unitPrice)` 로 전환됐다.
- `unitPrice` 인자는 현재 `Order.totalPrice` 누적에만 쓰이고 `OrderItem` 컬럼에는 저장되지 않는다. 즉 결제 시점 가격이 entity 에 보존되지 않고 휘발한다.
- e-commerce 표준 패턴은 결제 시점 가격 snapshot (`Product.price` 가 추후 변경되어도 영수증 / 환불 / 정산 / 통계는 그 당시 가격 기준) 인데 현재 구조는 그것을 보장하지 못한다.
- 본 series (Issue #195 — Stock #199 / Order #200 / Payment #202 / FK cleanup #203) 의 메타 원칙이 "schema 변경 0건" 이었기 때문에 본 정비는 별도 후속 트랙으로 분리됐다 (`docs/tasks/order-jpa-association-decouple/retrospective.md` "아쉬운 점", `docs/tasks/order-jpa-association-decouple/adr.md` 결정 3 "후속 정비 항목").
- 관련 이슈: GitHub Issue #201.

## 목표

- `OrderItem.unitPrice` 컬럼을 entity / schema 양쪽에 추가하여 결제 시점 가격을 영구 보존한다.
- `Product.price` 가 추후 변경되어도 기존 `OrderItem` 의 단가가 보존되어 영수증 / 환불 / 정산 / 통계가 결제 시점 기준으로 재구성 가능해진다.
- `OrderItem.java` 의 미해결 주석 (`// 가격도 넣어야 하나? ...`) 을 결정으로 명문화한다.

## 범위

### 포함 범위

- `OrderItem` entity 에 `unitPrice: int` 컬럼 추가
- Flyway V5 migration 으로 `tbl_order_item.unit_price` 컬럼 신설
- 기존 row 의 `unit_price` 를 `tbl_product.price` JOIN 으로 backfill 후 NOT NULL 전환
- `Order.addOrderItem` 내부에서 `OrderItem.of(...)` 로 `unitPrice` 가 흘러가도록 정리 (시그니처 자체는 변경 없음)
- `OrderItem.of(...)` 시그니처에 `unitPrice` 추가
- `OrderItem.java` 의 미해결 주석 제거 + 결정 명문화
- 단위 테스트 (`OrderTest`) 에 snapshot 보존 assertion 추가
- 슬라이스 테스트 (`OrderRepositoryJpaAdapterTest`) round-trip 검증 보강
- 본 task adr 신규 작성, `docs/adr.md` Task ADR 색인 표 갱신, `docs/db-schema.md` 의 `tbl_order_item` 섹션 갱신

### 제외 범위

- 응답 DTO 노출 (영수증 / 주문 조회 응답에 unitPrice 노출). 사용처가 생길 때 별도 PR.
- `Money` VO 도입. 본 task 는 `int` 통일.
- `Product.price` 변동 이력 / 가격 정책 관리.
- 운영 DB 배포 절차. Flyway 적용은 일반 배포 흐름에 위임.
- `addOrderItem` 시그니처 변경 / 테스트 fixture 의 호출 형태 변경.

## 주요 시나리오

1. 사용자가 주문을 생성한다 → 호출부가 `product.getPrice()` 를 `addOrderItem` 의 `unitPrice` 인자로 넘긴다 → `OrderItem.unitPrice` 컬럼에 결제 시점 가격이 저장된다.
2. 운영자가 `Product.price` 를 변경한다 → 기존 `OrderItem.unitPrice` 는 결제 시점 가격을 그대로 유지한다.
3. 배포 시 V5 migration 이 적용된다 → 기존 row 의 `unit_price` 가 `tbl_product.price` 와 JOIN 되어 채워진 뒤 NOT NULL 로 전환된다.

## 요구사항

- `OrderItem.unitPrice` 는 `int` 타입이며 NOT NULL.
- `addOrderItem(productId, quantity, unitPrice)` 호출 시 인자 그대로의 `unitPrice` 가 `OrderItem.unitPrice` 에 저장된다.
- `Product.price` 가 호출 이후 변경되어도 기존 `OrderItem.unitPrice` 가 보존된다 (snapshot 의미).
- Hibernate `validate` 가 새 컬럼을 schema 와 일치한다고 인식한다.

## 제약사항

- `Order.addOrderItem(Long productId, int quantity, int unitPrice)` 시그니처를 변경하지 않는다. 호출부 (production 3곳 / test 10여 곳) 영향 차단.
- `int` 타입을 채택한다. 기존 `Order.totalPrice` / `Payment.amount` 가 `int` 이고 `Product.price` 도 `int` 이므로 통일.
- 본 PR 범위 안에서 응답 DTO 를 노출하지 않는다.
- 머지된 series task 폴더 (`order-jpa-association-decouple`, `payment-jpa-association-decouple`, `cross-aggregate-fk-cleanup`) 의 문서는 수정하지 않는다 (완료 task 폴더 불변 원칙).
