# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cross-aggregate-fk-cleanup/prd.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/architecture.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/adr.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/api-spec.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/db-schema.md`
- step1 에서 생성된 파일:
  - `/src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql`

루트 docs 동기화 대상 파일도 모두 읽는다.

- `/docs/adr.md` (ADR-020 본문 및 series 후속 노트 위치 파악)
- `/docs/db-schema.md` (FK 표기 위치 파악)
- `/docs/architecture.md` (stock / order / payment 도메인 섹션 위치 파악)

선행 series 마무리 시점의 표현 일관성을 위해 아래 회고도 참조한다.

- `/docs/tasks/payment-jpa-association-decouple/retrospective.md` (series 전체 baseline 표현)

## 작업

step1 의 V4 migration 으로 cross-aggregate FK 5건이 schema 에서 제거된 사실을 루트 docs 3건에 반영한다.

### `docs/adr.md` — ADR-020 후속 노트 1건 추가

- ADR-020 본문 (현재 마지막 후속 노트가 `payment-jpa-association-decouple, 2026-06-03` 인 위치) 뒤에 cross-aggregate FK 일괄 제거 완료 후속 노트 1건을 추가한다.
- 추가 노트의 내용 가이드 (실제 문장은 worker 가 series 마무리 톤으로 작성):
  - cross-aggregate FK 5건 (`fk_stock_product_id`, `fk_stock_history_stock_id`, `fk_order_member_id`, `fk_order_item_product_id`, `fk_payment_order_id`) 을 단일 Flyway V4 migration 으로 일괄 제거.
  - UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 과 same-aggregate FK (`fk_order_item_order_id`) 는 유지.
  - 코드 + DB schema 정합성 회복으로 ADR-020 후속 트랙 (Stock / Order / Payment / FK cleanup) series 완전 종료.
  - 운영 DB 의 FK 제거 적용 절차는 별도 결정.
  - 세부 결정은 `docs/tasks/cross-aggregate-fk-cleanup/adr.md` 참조.
- adr.md 의 Task ADR 색인 표 (현재 stock / order / payment-jpa-association-decouple 행 위치) 에 `cross-aggregate-fk-cleanup` 한 행 추가. 정렬은 기존 행과 동일한 알파벳 / 카테고리 순서 컨벤션을 따른다.

### `docs/db-schema.md` — FK 표기 정비

- 다음 5개 FK 표기를 제거한다. UNIQUE 표기는 유지하고, 필요하다면 "UNIQUE" 만 남기는 식으로 정리.
  - `tbl_stock` 의 `product_id (FK -> tbl_product.id, UNIQUE)` → `product_id (UNIQUE)` 또는 `product_id` + 별도 줄 / 주석으로 1:1 invariant 표기.
  - `tbl_stock_history` 의 `stock_id (FK -> tbl_stock.id)` → `stock_id` (단순 ID 컬럼).
  - `tbl_order` 의 `member_id (FK -> tbl_member.id)` → `member_id`.
  - `tbl_order_item` 의 `product_id (FK -> tbl_product.id)` → `product_id`.
  - `tbl_payment` 의 `order_id (FK -> tbl_order.id, UNIQUE)` → `order_id (UNIQUE)`.
- **same-aggregate FK 표기는 그대로 유지**: `tbl_order_item` 의 `order_id (FK -> tbl_order.id)` 는 ADR-020 범위 밖이므로 손대지 않는다.
- 정비 톤: cart 섹션 (line 127 부근) 의 "`member_id`, `product_id` 는 FK 제약을 두지 않는다. cart 도메인은 다른 aggregate 를 `Long` ID 로만 참조한다 (ADR-020)" 와 일관된 표현을 본 5건 정비 부분에 사용 가능. 단, 본 태스크의 변경 정신 ("cross-aggregate FK 일괄 제거 완료") 이 명확히 드러나야 한다.
- db-schema.md 상단 또는 적절한 위치에 본 정비의 정책 근거 (ADR-020 후속 트랙 / 단일 V4 migration / UNIQUE 와 same-aggregate FK 유지) 를 짧게 명시한다.

### `docs/architecture.md` — series 마무리 표현

- 본 series 마무리를 architecture.md 의 stock / order / payment 도메인 섹션 (line 141 ~ 144 부근) 에 반영한다. 현재 각 섹션 끝에 series 진행 / 후속 트랙 표현이 있으므로, 마무리 사실 ("cross-aggregate FK 일괄 제거로 코드 + DB schema 정합성 회복") 을 series 종결 시점 표현으로 정비한다.
- 또는 architecture.md 의 적절한 상위 섹션에 series 마무리 단락 (cross-aggregate ID 참조 정책의 코드 + schema 정합성 완성) 을 추가.
- 운영 DB 배포가 별도 결정으로 분리됐다는 사실도 짧게 명시.

## 수정 가능 경로

- `docs/adr.md`
- `docs/db-schema.md`
- `docs/architecture.md`
- `docs/tasks/cross-aggregate-fk-cleanup/**` (필요 시 보정)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/adr.md` 에 ADR-020 의 본 트랙 후속 노트가 1건 추가됐는가?
     - `grep -n "cross-aggregate-fk-cleanup\|FK 일괄 제거\|fk_stock_product_id" docs/adr.md` 로 후속 노트와 색인 행 추가 확인.
   - `docs/db-schema.md` 에서 5개 cross-aggregate FK 표기가 제거됐는가?
     - `grep -nE "fk_stock_product_id|fk_stock_history_stock_id|fk_order_member_id|fk_order_item_product_id|fk_payment_order_id" docs/db-schema.md` 결과 0건.
     - `grep -n "FK -> tbl_product\|FK -> tbl_stock\|FK -> tbl_member" docs/db-schema.md` 로 표기 정비 확인. (단, `tbl_order_item.order_id (FK -> tbl_order.id)` 표기는 1건 남아 있어야 함 — same-aggregate FK 유지)
   - `docs/db-schema.md` 의 UNIQUE 표기 (`uk_stock_product_id`, `uk_payment_order_id` 정신) 가 그대로 유지되는가?
   - `docs/architecture.md` 의 stock / order / payment 섹션에 series 마무리 표현이 반영됐는가?
   - 완료된 task 폴더 (`docs/tasks/stock-jpa-association-decouple/`, `docs/tasks/order-jpa-association-decouple/`, `docs/tasks/payment-jpa-association-decouple/`) 가 수정되지 않았는가?
     - `git diff --name-only docs/tasks/stock-jpa-association-decouple docs/tasks/order-jpa-association-decouple docs/tasks/payment-jpa-association-decouple` 결과 0건.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 완료된 task 폴더 (`docs/tasks/stock-jpa-association-decouple/`, `docs/tasks/order-jpa-association-decouple/`, `docs/tasks/payment-jpa-association-decouple/`) 의 ADR / retrospective / phases 를 수정하지 마라. 이유: 완료된 tasks 불변 원칙 (CLAUDE.md / `docs/tasks/README.md` / ADR 결정 5).
- `docs/db-schema.md` 의 `tbl_order_item.order_id (FK -> tbl_order.id)` 표기를 제거하지 마라. 이유: same-aggregate FK 는 본 트랙 범위 밖 (ADR 결정 3).
- `docs/db-schema.md` 의 UNIQUE 제약 표기 (`uk_stock_product_id` / `uk_payment_order_id` 정신) 를 제거하지 마라. 이유: 도메인 invariant 유지 (ADR 결정 2).
- 운영 DB 배포 절차를 docs 에 박지 마라. 이유: 본 PR 범위 밖 (ADR 결정 4). "운영 배포는 별도 결정" 정도 한 줄 표현만 허용.
- ADR-020 본문 또는 본 후속 노트에서 lag 표준 정책을 정의하지 마라. 이유: 표본 1건으로 표준화하지 않기로 Discuss 단계에서 결정.
- 기존 테스트를 깨뜨리지 마라.
