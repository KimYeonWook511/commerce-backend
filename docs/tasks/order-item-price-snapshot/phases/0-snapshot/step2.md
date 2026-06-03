# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/order-item-price-snapshot/prd.md`
- `/docs/tasks/order-item-price-snapshot/architecture.md`
- `/docs/tasks/order-item-price-snapshot/adr.md`
- `/docs/tasks/order-item-price-snapshot/api-spec.md`
- `/docs/tasks/order-item-price-snapshot/db-schema.md`
- `/src/main/resources/db/migration/V5__add_order_item_unit_price.sql` (이전 step 에서 신규)
- `/src/main/java/com/commerce/order/domain/OrderItem.java` (이전 step 에서 수정)

추가 컨텍스트:

- `/docs/ADR.md` (Task ADR 색인 표 / ADR-024 Flyway / ADR-020 cross-aggregate)
- `/docs/db-schema.md` (`tbl_order_item` 섹션 — 107~120 행 근방, V4 비고)

## 작업

step 1 에서 entity / migration / 단위 테스트 변경을 마쳤다. 이번 step 은 그 결과를 루트 docs 에 반영한다.

### `docs/ADR.md` 갱신

- 파일 상단의 "Task ADR 색인" 표 (5행 근방에서 시작) 에 본 task 행을 알파벳 순서로 추가한다. 위치는 `order-jpa-association-decouple` 행 다음 (`payment-attempt-idempotency` 앞).
- 추가할 행:

```
| order-item-price-snapshot | [`docs/tasks/order-item-price-snapshot/adr.md`](tasks/order-item-price-snapshot/adr.md) | OrderItem.unitPrice 컬럼 신설로 결제 시점 가격 snapshot 보존, V5 migration JOIN backfill, 응답 DTO 노출은 별도 PR (PR #200 / Issue #201 후속) |
```

- ADR-026 등 본문 ADR 을 신규로 만들지 마라. 본 결정은 도메인-specific 이라 task adr 로만 관리한다 (`docs/ADR.md` 상단 정책 문장 참고).
- 기존 표의 다른 행 / 본문 ADR 을 수정하지 마라.

### `docs/db-schema.md` 갱신

- `tbl_order_item` 섹션 (107~120 행 근방) 의 컬럼 목록에 `unit_price INT NOT NULL` 한 줄을 `quantity` 뒤에 추가한다.
- 본 섹션의 비고 영역에 한 줄을 추가한다:
  - `unit_price 는 V5 migration 으로 신설된 결제 시점 가격 snapshot 컬럼이다. Product.price 변동 후에도 결제 시점 단가가 보존된다. 세부 결정은 docs/tasks/order-item-price-snapshot/adr.md 참조.`
- 파일 상단 / 다른 곳에 V5 의 한 줄 요약을 V4 표기 형식과 일관되게 추가한다 (V4 처럼 "Flyway migration" 항목이 따로 있다면 그 아래에).

### 본 task 범위에서 손대지 않을 곳

- `docs/PRD.md` 는 수정하지 마라. PRD 는 기능 범위 문서이고 snapshot 정책은 ADR 영역. 중복 표현 회피 (task adr 결정 1 의 의도).
- `docs/architecture.md` 는 수정하지 마라. 본 task 는 도메인 entity 한 컬럼 추가라 아키텍처 다이어그램이나 모듈 경계에 영향 없음.
- `docs/api-spec.md` 는 수정하지 마라. API 계약 변경 없음 (task api-spec 참조).
- 머지된 task 폴더 (`docs/tasks/order-jpa-association-decouple/*`, `docs/tasks/payment-jpa-association-decouple/*`, `docs/tasks/cross-aggregate-fk-cleanup/*`) 를 수정하지 마라.

## 수정 가능 경로

- `docs/ADR.md`
- `docs/db-schema.md`
- `docs/tasks/order-item-price-snapshot/**` (필요 시 task 문서 보정만)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/ADR.md` Task ADR 색인 표에 `order-item-price-snapshot` 행이 1개 추가됐는가?
     - `grep "order-item-price-snapshot" docs/ADR.md` 결과 ≥ 1.
   - 본문 ADR (ADR-026 등) 이 신규 추가되지 않았는가?
     - `grep -c "^### ADR-" docs/ADR.md` 결과가 step 1 전과 동일.
   - `docs/db-schema.md` 의 `tbl_order_item` 섹션에 `unit_price` 가 추가됐는가?
     - `grep "unit_price" docs/db-schema.md` 결과 ≥ 1.
   - `docs/PRD.md` / `docs/architecture.md` / `docs/api-spec.md` 가 변경되지 않았는가?
     - `git diff --name-only docs/PRD.md docs/architecture.md docs/api-spec.md` 결과 0건.
   - main / test 자바 코드가 본 step 에서 변경되지 않았는가?
     - `git diff --name-only HEAD -- src/main/java src/test/java` (step 1 commit 이후) 결과 0건.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/ADR.md` 본문에 ADR-026 같은 신규 본문 ADR 을 만들지 마라. 이유: 본 결정은 도메인-specific 이라 task adr 로만 관리한다 (`docs/ADR.md` 상단 정책).
- `docs/PRD.md`, `docs/architecture.md`, `docs/api-spec.md` 를 수정하지 마라. 이유: 본 task 가 PRD / 아키텍처 / API 계약에 영향 없음.
- 머지된 task 폴더의 문서 (`docs/tasks/order-jpa-association-decouple/*`, `docs/tasks/payment-jpa-association-decouple/*`, `docs/tasks/cross-aggregate-fk-cleanup/*`) 를 수정하지 마라. 이유: 완료 task 폴더 불변 원칙.
- 본 step 에서 main / test 자바 코드를 수정하지 마라. 이유: step 1 의 책임. 본 step 은 루트 docs 동기화만 한다.
- 기존 테스트를 깨뜨리지 마라.
