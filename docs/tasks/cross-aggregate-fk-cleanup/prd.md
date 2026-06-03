# 태스크 PRD

## 태스크명

- `cross-aggregate-fk-cleanup`

## 배경

- Issue #195 (ADR-020 후속 트랙) 의 코드 마이그레이션 sub-PR 3건 (`stock-jpa-association-decouple` #199 / `order-jpa-association-decouple` #200 / `payment-jpa-association-decouple` #202) 이 모두 머지 완료됐고, Issue #195 도 close됐다.
- 선행 series 의 메타 원칙 ("코드 차원 association 해제만, schema 변경 0건", `docs/tasks/stock-jpa-association-decouple/adr.md` 결정 3) 으로 보류해둔 **DB FK 제약 일괄 제거 트랙** 이다.
- 선행 sub-PR 의 ADR / 회고가 모두 "FK 일괄 제거는 별도 트랙에서 진행한다" 로 마무리됐고, 본 트랙이 그 마무리 단계다.

## 목표

- 선행 series 가 JPA 매핑에서 해제한 cross-aggregate 의 DB FK 제약 5건을 단일 Flyway migration 으로 일괄 제거한다.
- 코드와 DB schema 의 정합성 (둘 다 cross-aggregate 객체 참조 없음) 을 회복한다.
- 잔존 도메인 invariant (Stock 1:1 Product, Payment 1:1 Order) 와 same-aggregate 관계 (Order ↔ OrderItem) 의 schema 제약은 그대로 유지한다.

## 범위

### 포함 범위

- `V4__drop_cross_aggregate_fk_constraints.sql` Flyway migration 추가. `ALTER TABLE ... DROP FOREIGN KEY ...` 5건.
  - `tbl_stock` → `fk_stock_product_id`
  - `tbl_stock_history` → `fk_stock_history_stock_id`
  - `tbl_order` → `fk_order_member_id`
  - `tbl_order_item` → `fk_order_item_product_id`
  - `tbl_payment` → `fk_payment_order_id`
- Hibernate `validate` 통과 확인 — `./gradlew integrationTest` (Testcontainers MySQL 에서 Flyway 적용 후 entity 매핑 검증).
- 루트 docs 동기화 — `docs/ADR.md` ADR-020 후속 노트, `docs/db-schema.md` FK 표기 정비, `docs/architecture.md` series 마무리.
- 회고록 작성 — series 전체 (Stock / Order / Payment / 본 FK cleanup) 4 트랙 마무리 시점의 baseline 기록.

### 제외 범위

- **운영 DB 의 FK 제거 적용 절차** — Issue #195 본문이 "운영 DB 의 FK 제거 적용은 별도 결정" 으로 분리. 본 PR 은 Flyway migration 파일 추가 + local/test 검증까지. 운영 배포 시점·절차·무중단 여부는 후속 결정으로 둔다.
- **잔존 UNIQUE 제약** — `uk_stock_product_id`, `uk_payment_order_id` 는 Stock 1:1 Product / Payment 1:1 Order 도메인 invariant 이므로 유지. JPA `@Table(uniqueConstraints = ...)` 매핑도 그대로.
- **잔류 KEY index** — MySQL FK 가 자동 생성하거나 별도 선언된 동명 index (`fk_stock_history_stock_id`, `fk_order_item_product_id`, `fk_order_member_id` 의 InnoDB 자동 index) 는 조회 보조용으로 유지. 본 PR 에서 DROP 하지 않는다.
- **same-aggregate FK** — `fk_order_item_order_id` (Order ↔ OrderItem) 는 ADR-020 의 적용 범위 밖이므로 그대로 유지.
- **코드 변경** — 본 PR 은 schema 만 변경. main / test 자바 코드는 1줄도 바뀌지 않는다.
- **결제 시점 가격 snapshot** — Issue #201 별도 트랙.

## 주요 시나리오

- 본 PR 머지 후 local / test 환경에서 Flyway 가 V4 migration 을 적용한다.
- Testcontainers MySQL 컨테이너에서 `integrationTest` 가 schema (FK 5건 제거됨) 와 JPA entity 매핑 (cross-aggregate association 0건) 의 정합성을 검증한다.
- 기존 비즈니스 동작 (재고 차감 / 주문 생성 / 결제 승인 등) 은 코드 무변경이므로 회귀 없음.

## 요구사항

- Flyway V4 migration 이 `./gradlew integrationTest` 실행 시 적용되고 schema 가 5개 FK 없는 상태가 된다.
- Hibernate `validate` 가 통과한다 — entity 매핑이 schema 와 일치.
- `./gradlew test` 와 `./gradlew integrationTest` 모두 통과.
- 코드 변경 0건. `git diff src/main/java src/test/java` 결과 없음.

## 제약사항

- UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 을 DROP 하지 않는다. 도메인 invariant 보존.
- 동명 KEY index (`fk_stock_history_stock_id`, `fk_order_item_product_id`, `fk_order_member_id`) 를 DROP 하지 않는다. 조회 보조용 유지.
- same-aggregate FK (`fk_order_item_order_id`) 를 DROP 하지 않는다. ADR-020 범위 밖.
- 완료된 task 폴더 (`docs/tasks/stock-jpa-association-decouple/`, `docs/tasks/order-jpa-association-decouple/`, `docs/tasks/payment-jpa-association-decouple/`) 의 ADR / retrospective 를 사후 수정하지 않는다 — 완료된 tasks 불변 원칙 (`docs/tasks/README.md`).
