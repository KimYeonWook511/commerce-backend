# 태스크 아키텍처

## 개요

- 본 태스크는 **DB schema 만 변경**한다. main / test 자바 코드는 1줄도 변경하지 않는다.
- 변경 대상은 `src/main/resources/db/migration/` 의 Flyway V 파일 1개 추가 + 루트 docs 동기화 + 회고록 작성이다.
- 선행 series (Stock #199 / Order #200 / Payment #202) 가 JPA 매핑 차원에서 cross-aggregate association 을 모두 해제한 상태에서 시작하므로, DB FK 제약을 제거해도 entity 매핑과의 정합성에 영향이 없다.

## 변경 대상

### Flyway migration

- `src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql` (신규)
  - `ALTER TABLE tbl_stock DROP FOREIGN KEY fk_stock_product_id;`
  - `ALTER TABLE tbl_stock_history DROP FOREIGN KEY fk_stock_history_stock_id;`
  - `ALTER TABLE tbl_order DROP FOREIGN KEY fk_order_member_id;`
  - `ALTER TABLE tbl_order_item DROP FOREIGN KEY fk_order_item_product_id;`
  - `ALTER TABLE tbl_payment DROP FOREIGN KEY fk_payment_order_id;`

### Domain / Application / Repository / Test 코드

- 변경 없음. 본 태스크의 정책 목적은 schema 정합성 회복이며, 코드는 선행 series 에서 이미 cross-aggregate association 이 모두 해제된 상태다.

### 루트 docs

- `docs/ADR.md` — ADR-020 본문에 series FK 일괄 제거 완료 후속 노트 1건 추가.
- `docs/db-schema.md` — 5개 FK 표기 (`tbl_stock.product_id (FK -> ...)`, `tbl_stock_history.stock_id (FK -> ...)`, `tbl_order.member_id (FK -> ...)`, `tbl_order_item.product_id (FK -> ...)`, `tbl_payment.order_id (FK -> ..., UNIQUE)`) 에서 `FK -> ...` 부분 제거. UNIQUE 표기 유지. same-aggregate `tbl_order_item.order_id (FK -> tbl_order.id)` 는 유지.
- `docs/architecture.md` — 코드 + DB schema 정합성 회복 (cross-aggregate FK 0건, ID 참조 일관) 명시.

### task 회고

- `docs/tasks/cross-aggregate-fk-cleanup/retrospective.md` — series (Stock / Order / Payment / 본 FK cleanup) 4 트랙 마무리 시점의 baseline 기록.

## 설계 방향

### 단일 V 파일

- 5개 FK 를 단일 V4 파일에 묶는다. 본 트랙의 정책 단위가 "series 마무리 한 건" 이고, 도메인별 분리는 정책 단위와 어긋난다.
- 5개 FK 가 모두 `V1__init.sql` 한 파일에 정의됐던 점도 단일 V4 와 일관.

### UNIQUE / 잔류 KEY index 유지

- UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 은 Stock 1:1 Product, Payment 1:1 Order 도메인 invariant 표현. FK 제거와 무관하게 유지.
- MySQL InnoDB 가 FK 제거 후에도 동명 KEY index 를 남기는 경우 (`fk_stock_history_stock_id`, `fk_order_item_product_id`) 는 그대로 둔다. 조회 보조용으로 유지해도 무해하며, 운영 lock 단위가 최소화된다.
- `tbl_order.fk_order_member_id` 는 `uk_order_member_idempotency (member_id, idempotency_key)` 복합 UNIQUE 의 leftmost prefix 를 InnoDB 가 재사용했으므로 FK DROP 후 잔류 index 가 없다.

### same-aggregate FK 유지

- `fk_order_item_order_id` (Order ↔ OrderItem) 는 ADR-020 의 적용 범위 ("같은 aggregate 내 root-child 는 객체 참조 허용") 밖이다. 본 태스크에서 DROP 하지 않는다.

### Hibernate validate 통과 검증

- 선행 series 가 모든 `@ManyToOne` / `@OneToOne` cross-aggregate association 을 해제했으므로 entity 매핑이 FK 정보를 들고 있지 않다.
- column 매핑 (`@Column(name = "product_id", nullable = false)` 등) 은 그대로 유지되며, FK 제거 후 schema 와 매핑 모두 일관된 "컬럼만 존재" 상태가 된다.
- `./gradlew integrationTest` (Testcontainers MySQL + Flyway 자동 적용 + ddl-auto: validate) 가 통과해야 본 태스크의 정합성 보증이 완료된다.

## 데이터 흐름

- 코드 변경 없음 → 비즈니스 흐름 (재고 차감 / 주문 생성 / 결제 승인 / 보상) 모두 기존 그대로.
- DB 차원의 referential integrity 보증이 FK 5건 만큼 약해지지만, 선행 series 에서 application 검증 (`PRODUCT_NOT_FOUND`, `ORDER_NOT_FOUND` 등) 으로 이미 cross-aggregate 정합성을 확보한 상태다 (cart 도메인의 `CART_ITEM_PRODUCT_NOT_FOUND` 패턴과 동일).

## 예외 및 실패 처리

- 본 태스크 자체는 예외 흐름 변경 없음.
- FK 제거 후 referential integrity 위반 시 DB unique / FK violation 이 더 이상 발생하지 않는다. application 의 존재 검증 (e.g. `productRepository.findById(productId).orElseThrow(PRODUCT_NOT_FOUND)`) 이 1차 방어선이고, DB unique 위반은 안전망 500 위임 (`ADR-011`) 으로 처리.

## 테스트 포인트

- `./gradlew test` — 단위 / 슬라이스 테스트 통과.
- `./gradlew integrationTest` — Flyway V4 migration 적용 + Hibernate validate 통과 + 기존 docker 태그 테스트 회귀 없음.
- 코드 무변경이므로 concurrency / batch / sandbox 격리 task 는 수동 실행 필요 없음.
