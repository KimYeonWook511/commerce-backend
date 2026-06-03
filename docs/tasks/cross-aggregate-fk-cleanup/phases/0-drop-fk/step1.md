# Step 1: drop-fk-migration

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cross-aggregate-fk-cleanup/prd.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/architecture.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/adr.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/api-spec.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/db-schema.md`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/ADR.md` (ADR-020 — 신규 도메인 cross-aggregate ID 참조)
- `/docs/db-schema.md`
- `/docs/tasks/stock-jpa-association-decouple/adr.md` (series 메타 원칙 / schema 무변경 원칙 정립)
- `/docs/tasks/payment-jpa-association-decouple/retrospective.md` (series 전체 baseline)

현재 schema 와 entity 매핑 상태를 파악하기 위해 아래 파일도 읽는다.

- `/src/main/resources/db/migration/V1__init.sql`
- `/src/main/resources/db/migration/V2__align_payment_key_lengths.sql`
- `/src/main/resources/db/migration/V3__rename_payment_attempt_payment_id.sql`
- `/src/main/java/com/commerce/stock/domain/Stock.java`
- `/src/main/java/com/commerce/stock/domain/StockHistory.java`
- `/src/main/java/com/commerce/order/domain/Order.java`
- `/src/main/java/com/commerce/order/domain/OrderItem.java`
- `/src/main/java/com/commerce/payment/domain/Payment.java`

## 작업

cross-aggregate FK 제약 5건을 단일 Flyway V4 migration 으로 일괄 제거한다. 코드 변경은 0건이며, schema 만 변경한다.

### Flyway migration 추가

- 신규 파일: `src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql`
- 내용: 다음 5개 `ALTER TABLE ... DROP FOREIGN KEY ...` SQL 을 한 파일에 포함한다.

```sql
ALTER TABLE `tbl_stock` DROP FOREIGN KEY `fk_stock_product_id`;
ALTER TABLE `tbl_stock_history` DROP FOREIGN KEY `fk_stock_history_stock_id`;
ALTER TABLE `tbl_order` DROP FOREIGN KEY `fk_order_member_id`;
ALTER TABLE `tbl_order_item` DROP FOREIGN KEY `fk_order_item_product_id`;
ALTER TABLE `tbl_payment` DROP FOREIGN KEY `fk_payment_order_id`;
```

SQL 작성 규칙:
- backtick 인용 (`` ` ``) 으로 식별자 감싸기 — V1__init.sql 의 컨벤션과 일관.
- 각 SQL 1줄. `;` 로 종료.
- DROP 대상은 정확히 위 5건. **다른 FK / KEY / UNIQUE / 컬럼 / 테이블** 을 건드리지 마라.

### 코드 변경

- main / test 자바 코드 변경 없음. JPA entity 매핑은 선행 series 에서 이미 cross-aggregate association 이 모두 해제된 상태다.
- application 설정 파일 (`application.yml`, `application-test.yml`, `application-local.yml`) 변경 없음.

## 수정 가능 경로

- `src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql` (신규 파일만)
- `docs/tasks/cross-aggregate-fk-cleanup/**` (필요 시 보정)

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql` 가 추가됐고, `DROP FOREIGN KEY` 5건만 포함하는가?
     - `grep -c "DROP FOREIGN KEY" src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql` 결과 5.
   - V4 SQL 이 `DROP INDEX`, `DROP KEY`, `DROP COLUMN`, `DROP TABLE` 명령을 포함하지 않는가?
     - `grep -E "DROP (INDEX|KEY|COLUMN|TABLE)" src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql` 결과 0건.
   - main / test 자바 코드가 변경되지 않았는가?
     - `git diff --name-only src/main/java src/test/java` 결과 0건.
   - `integrationTest` 가 Testcontainers MySQL 에 V4 까지 모두 적용하고 Hibernate `validate` 를 통과하는가?
   - same-aggregate FK (`fk_order_item_order_id`) 가 schema 에 그대로 남아있는가?
     - `grep "fk_order_item_order_id" src/main/resources/db/migration/V1__init.sql` 결과 유지, V4 에는 없음.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 을 DROP 하지 마라. 이유: Stock 1:1 Product, Payment 1:1 Order 도메인 invariant 는 FK 와 무관하게 유지된다 (ADR 결정 2).
- 동명 KEY index (`fk_stock_history_stock_id`, `fk_order_item_product_id`, `fk_order_member_id` 의 InnoDB 자동 index) 를 DROP 하지 마라. 이유: 조회 보조용으로 유지. ALTER 횟수 최소화로 운영 lock 단위를 줄이는 결정 (ADR 결정 2).
- same-aggregate FK `fk_order_item_order_id` 를 DROP 하지 마라. 이유: Order ↔ OrderItem same-aggregate 관계는 ADR-020 범위 밖이고 본 태스크 범위 밖이다 (ADR 결정 3).
- 자바 코드 (main / test) 를 수정하지 마라. 이유: 본 태스크의 정책 목적은 schema 정합성 회복뿐이며, 코드 association 해제는 선행 series 에서 이미 완료됐다.
- V1 / V2 / V3 기존 Flyway 파일을 수정하지 마라. 이유: 이미 적용된 migration 은 immutable. 변경은 항상 새 V 파일로 표현한다.
- Flyway V4 외에 추가 V 파일을 만들지 마라. 이유: 본 태스크의 정책 단위는 단일 V 파일 일괄 제거 (ADR 결정 1).
- 운영 DB 배포 절차나 lock 분석 문서를 추가하지 마라. 이유: 본 PR 범위 밖 (ADR 결정 4).
- 기존 테스트를 깨뜨리지 마라.
