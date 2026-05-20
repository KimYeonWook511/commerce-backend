# Step 6: docs-sync

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/features/stock-management/prd.md`
- `docs/features/stock-management/architecture.md`
- `docs/features/stock-management/adr.md`
- `docs/features/stock-management/api-spec.md`
- `docs/features/stock-management/db-schema.md`
- `docs/features/stock-management/phases/0-admin-stock-management-api/step5.md`
- `docs/architecture.md`
- `docs/api-spec.md`
- `docs/db-schema.md`
- `docs/ADR.md`
- `docs/features/TEMP_TODO.md`

## 작업

- 구현 결과와 feature 문서를 기준으로 루트 문서를 동기화한다.
  - `docs/architecture.md`: `stock` 도메인 책임과 관리자 재고 관리 흐름 추가
  - `docs/api-spec.md`: 관리자 재고 등록, 증가, 감소, 이력 조회 API 추가
  - `docs/db-schema.md`: `tbl_stock_history`와 인덱스, 주요 관계 추가
  - `docs/ADR.md`: 필요하면 관리자 재고 관리와 이력 저장 결정 추가
- `docs/features/TEMP_TODO.md`의 재고 관리 항목은 구현 완료 상태와 충돌하지 않도록 정리한다.
  - 단, 후속 기능인 pagination, 관리자 UI, 주문 취소 재고 복구 고도화는 완료 처리하지 않는다.
- feature 문서와 루트 문서의 API 경로, enum 값, 응답 필드명이 서로 일치하는지 확인한다.

## 수정 가능 경로

- `docs/features/stock-management/**`
- `docs/features/TEMP_TODO.md`
- `docs/architecture.md`
- `docs/api-spec.md`
- `docs/db-schema.md`
- `docs/ADR.md`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래 탐색으로 문서 간 명칭이 일치하는지 확인한다.

```bash
rg "stock/histories|StockAdjustmentReason|tbl_stock_history|ORDER_CANCEL_RESTORE" docs src/main/java src/test/java
```

3. 아래를 확인한다.
   - 루트 API 스펙과 feature API 스펙의 endpoint가 일치하는가?
   - 루트 DB 스키마와 feature DB 스키마의 테이블/컬럼명이 일치하는가?
   - `TEMP_TODO.md`가 실제 구현 범위를 과장해서 완료 처리하지 않았는가?

## 금지사항

- 구현 코드 동작을 이 step에서 바꾸지 마라. 이유: 이 단계는 문서 동기화가 목적이다.
- `TEMP_TODO.md`에서 후속 기능까지 완료로 표시하지 마라. 이유: 구현 범위와 roadmap 상태가 불일치할 수 있다.
- Acceptance Criteria를 생략하지 마라. 이유: 문서 변경 후에도 전체 테스트 회귀를 확인해야 한다.
