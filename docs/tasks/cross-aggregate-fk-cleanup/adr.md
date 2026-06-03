# 태스크 ADR

## 결정 1: cross-aggregate FK 5건을 단일 Flyway V 파일로 일괄 제거한다

### 배경

- Issue #195 의 코드 마이그레이션 sub-PR 3건 (Stock #199 / Order #200 / Payment #202) 이 모두 머지된 상태에서, 보류해둔 DB FK 일괄 제거 트랙을 진행한다.
- 옵션:
  - (A) 도메인별 V 파일 3개 분리 (Stock 2 FK / Order 2 FK / Payment 1 FK)
  - (B) 단일 V4 파일에 5건 일괄

### 결정 내용

- (B) 단일 V4 파일 `V4__drop_cross_aggregate_fk_constraints.sql` 에 5개 FK DROP 을 묶는다.

### 근거

- 본 트랙의 정책 단위는 "series 마무리 한 건" — "한 트랙 = 한 PR = 한 migration" 이 자연스럽다.
- 5개 FK 가 모두 `V1__init.sql` 한 파일에 정의됐던 origin 도 단일 V4 와 일관.
- 도메인별 분리는 운영 lock 단위가 도메인 경계로 쪼개진다는 이점이 있으나, 본 PR 의 범위가 "Flyway 파일 추가 + local/test 검증" 이고 운영 배포 시점·절차는 별도 결정 (결정 4) 이므로 본 PR 에서 분리할 실익이 약하다.

### 결과

- 단일 V4 파일에 5개 `ALTER TABLE ... DROP FOREIGN KEY ...` SQL 묶음.
- PR 의 정책 메시지가 "cross-aggregate FK 일괄 제거" 로 단일하게 표현된다.

## 결정 2: UNIQUE 제약과 잔류 KEY index 는 유지한다

### 배경

- MySQL FK 제거 후 schema 잔류 항목:
  - `tbl_stock.uk_stock_product_id` UNIQUE — Stock 1:1 Product 도메인 invariant.
  - `tbl_payment.uk_payment_order_id` UNIQUE — Payment 1:1 Order 도메인 invariant.
  - `tbl_stock_history.KEY fk_stock_history_stock_id` — FK 와 동명 index, FK DROP 시 KEY 만 잔류.
  - `tbl_order_item.KEY fk_order_item_product_id` — 위와 동일.
  - `tbl_order.fk_order_member_id` 는 `uk_order_member_idempotency (member_id, idempotency_key)` 복합 UNIQUE 의 leftmost prefix 를 InnoDB 가 재사용해 별도 자동 index 가 생성되지 않았다 — FK DROP 후 잔류 index 가 없다.
- 옵션:
  - (A) FK 만 DROP, UNIQUE 와 잔류 KEY index 모두 유지
  - (B) FK + 불필요 KEY index 도 함께 DROP (UNIQUE 는 유지)

### 결정 내용

- (A) 를 채택한다.
- UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 은 도메인 invariant 표현으로 유지한다. JPA `@Table(uniqueConstraints = ...)` 매핑도 그대로.
- FK 컬럼의 index 는 모두 그대로 둔다 — FK 가 자동 생성한 동명 KEY index (`fk_stock_history_stock_id`, `fk_order_item_product_id`) 와 FK 가 재사용한 기존 UNIQUE index (`uk_stock_product_id`, `uk_order_member_idempotency`, `uk_payment_order_id`) 둘 다.

### 근거

- UNIQUE 는 FK 와 서로 다른 제약이며, "Stock 1:1 Product / Payment 1:1 Order" 라는 도메인 invariant 를 DB 차원에서 보증한다. FK 제거로 약해지지 않는다.
- **FK 와 index 는 서로 다른 레이어다** — FK 는 참조 무결성 제약, index 는 조회 성능 구조. FK DROP 으로 무결성 제약이 사라져도 그 컬럼의 index 는 application 의 `WHERE` / `JOIN` 조건으로 계속 필요하다. 본 PR 의 5개 FK 컬럼은 모두 application 조회·검증의 키 (e.g. Order 의 `WHERE member_id = ?`, Payment 의 `WHERE order_id = ?`, StockHistory 의 `WHERE stock_id = ?` 등) 로 사용되므로 index 가 없으면 full table scan 으로 떨어진다.
- 잔류 KEY index 와 재사용 index 는 조회 성능 보존 차원에서 유지한다 (storage / 쓰기 cost 미미). 본 PR 의 ALTER 횟수를 최소화해 운영 lock 단위를 줄이는 부수 효과도 있다.
- 운영 lock 영향을 줄이는 결정은 운영 배포 절차 결정 (결정 4) 의 자유도를 키운다 — index DROP 까지 묶지 않음으로써 향후 운영 배포 시 ALTER 단위가 작게 유지된다.

### 결과

- V4 SQL 은 `DROP FOREIGN KEY` 5건만 포함. `DROP INDEX` / `DROP KEY` 명령 0건.
- Stock·Payment 의 1:1 도메인 invariant 는 UNIQUE 제약으로 DB 차원에서도 그대로 보증된다.

## 결정 3: same-aggregate FK (`fk_order_item_order_id`) 는 범위 밖이다

### 배경

- `tbl_order_item.fk_order_item_order_id` 는 Order ↔ OrderItem same-aggregate 관계의 FK 다.
- ADR-020 의 적용 범위는 cross-aggregate 만이며, "같은 aggregate 내 root-child 관계는 객체 참조 허용 / FK 유지" 로 명시.
- 선행 Order sub-PR (#200) 결정 1 이 `Order.orderItems @OneToMany OrderItem` / `OrderItem.order @ManyToOne Order` 의 객체 참조 + cascade / orphanRemoval 을 유지하기로 결정.

### 결정 내용

- `fk_order_item_order_id` 는 본 태스크에서 DROP 하지 않는다.
- 본 태스크의 변경 범위는 cross-aggregate FK 5건 한정.

### 근거

- ADR-020 적용 범위 그대로.
- 선행 Order sub-PR 의 결정 1 (Order ↔ OrderItem same-aggregate 객체 참조 유지) 과 schema 차원에서도 일관.

### 결과

- `tbl_order_item.order_id` 컬럼의 FK 제약과 KEY index 는 모두 유지된다.
- `docs/db-schema.md` 에서 `tbl_order_item.order_id (FK -> tbl_order.id)` 표기도 그대로 유지한다.

## 결정 4: 운영 DB 의 FK 제거 적용 절차는 본 PR 범위 밖이다

### 배경

- Issue #195 본문 "운영 DB 의 FK 제거 적용 — schema 변경 적용은 별도 결정" 으로 분리.
- 본 PR 의 범위 옵션:
  - (A) Flyway 파일 추가 + local / test 검증까지
  - (B) 운영 DB 배포 절차 (lock 영향 분석 / 무중단 절차 / 롤백 절차) 까지 문서화

### 결정 내용

- (A) 를 채택한다.
- 본 PR 은 Flyway V4 파일 추가 + `./gradlew integrationTest` 로 Hibernate validate 통과 확인까지 진행한다.
- 운영 DB 배포 시점·절차·무중단 여부·롤백 정책은 본 PR 머지 후 별도 결정.

### 근거

- Issue #195 본문이 명시적으로 분리.
- 운영 배포 절차는 운영 DB 현황 (현재 데이터 양 / 트래픽 / 정비 윈도우 / 무중단 절차 표준) 에 의존하는 결정이라 본 PR 의 코드 / schema 정합성 회복 정책 목적과 다른 축이다.
- 본 PR 의 메시지가 "schema 정합성 회복" 한 가지로 단일하게 유지된다.

### 결과

- 본 PR 머지 후 local 개발 환경 / CI / test container 에서는 즉시 FK 5건 없는 schema 로 진입.
- 운영 DB 적용은 별도 운영 결정으로 분리. 본 PR ADR / 회고에 "운영 배포 절차는 후속 결정" 으로 명시한다.

## 결정 5: 완료된 task 폴더의 ADR / 회고는 사후 수정하지 않는다

### 배경

- 본 태스크가 series 전체 (Stock / Order / Payment / 본 FK cleanup) 의 마무리 트랙이라, 선행 sub-PR 의 ADR / 회고에 "FK 제거 완료" 같은 후속 노트를 부착하고 싶은 유혹이 있다.
- CLAUDE.md / `docs/tasks/README.md` 의 "완료된 tasks 불변 원칙": 머지 완료된 task 폴더 문서는 이후 수정하지 않는다.

### 결정 내용

- `docs/tasks/stock-jpa-association-decouple/` / `docs/tasks/order-jpa-association-decouple/` / `docs/tasks/payment-jpa-association-decouple/` 의 ADR / 회고를 수정하지 않는다.
- series 마무리 사실은 본 태스크의 `retrospective.md` 와 루트 `docs/ADR.md` 의 ADR-020 후속 노트로만 표현한다.

### 근거

- 프로젝트 컨벤션 그대로.
- 루트 `docs/` 가 "최신 상태의 단일 진실 원천" 이고, task 폴더는 머지 시점의 결정 기록.

### 결과

- 선행 sub-PR 의 task 폴더는 그대로 둔다.
- 본 태스크의 변경은 V4 SQL 1개 + 루트 docs 3개 + 본 태스크 폴더 신규 6개 (prd / architecture / adr / api-spec / db-schema / retrospective + phases) 로 좁게 유지된다.
