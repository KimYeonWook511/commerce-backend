# 기능 DB 스키마

## 개요

- 기존 `tbl_stock`을 사용해 상품별 현재 재고를 관리한다.
- 신규 `tbl_stock_history`를 추가해 관리자 재고 변경 이력을 저장한다.

## 신규 테이블

### `tbl_stock_history`

목적:
- 재고 변경 수량, 사유, 주체, 시점을 보존한다.

COLUMNS:
- `id (PK)`
- `stock_id (FK -> tbl_stock.id)`
- `quantity_change`
- `reason`
- `admin_member_id`
- `created_at`
- `updated_at`

## 변경 테이블

- 없음

## 인덱스

- 현재 JPA 엔티티와 마이그레이션 기준으로 별도 인덱스는 정의하지 않는다.
- 상품별 재고 이력 최신순 조회가 커지면 `idx_stock_history_stock_id_created_at (stock_id, created_at)` 추가를 검토한다.

## 데이터 무결성

- `stock_id`는 null이 아니어야 한다.
- `quantity_change`는 0이 아니어야 한다.
- `reason`은 null이 아니어야 한다.
- `admin_member_id`는 null이 아니어야 한다.
- `tbl_product`와 `tbl_stock`은 기존처럼 1:1 관계를 유지한다.

## 마이그레이션 고려사항

- 현재 레포지토리에 별도 DB 마이그레이션 도구는 없으므로 JPA 엔티티와 문서를 먼저 동기화한다.
- 기존 `tbl_stock` 데이터에 대한 과거 이력 백필은 이번 phase 범위에서 제외한다.
