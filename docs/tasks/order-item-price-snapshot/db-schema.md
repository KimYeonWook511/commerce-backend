# 태스크 DB 스키마

## 개요

- `tbl_order_item` 에 `unit_price INT NOT NULL` 컬럼을 추가한다.
- Flyway V5 (`V5__add_order_item_unit_price.sql`) 로 적용한다.
- 기존 row 는 `tbl_product.price` JOIN backfill 로 채운 뒤 NOT NULL 로 전환한다.

## 변경 테이블

### `tbl_order_item`

| 컬럼 | 변경 | 비고 |
|---|---|---|
| `unit_price` | 신규 (`INT NOT NULL`) | `quantity` 뒤. 결제 시점 단가 snapshot. |

기존 컬럼 (`id`, `order_id`, `product_id`, `quantity`, `created_at`, `updated_at`) 은 변경 없음.
기존 인덱스 / FK (`fk_order_item_order_id`, `KEY fk_order_item_product_id`) 도 변경 없음.

## 인덱스

- `unit_price` 에 인덱스를 추가하지 않는다. 조회 조건으로 사용되지 않는 결제 시점 단가 snapshot 컬럼.

## 데이터 무결성

- `unit_price` 는 NOT NULL.
- 음수 / 0 가드는 두지 않는다. `Product.price` 자체에 검증이 있고, 본 컬럼은 snapshot 의미라 도메인 검증을 중복하지 않는다 (task adr 결정 1).
- 본 컬럼은 한 번 저장되면 변경되지 않는다 (도메인 메서드 / setter 없음).

## 마이그레이션 고려사항

### 적용 순서

1. `ALTER TABLE tbl_order_item ADD COLUMN unit_price INT NULL AFTER quantity` — 우선 nullable 로 컬럼 추가.
2. `UPDATE tbl_order_item oi LEFT JOIN tbl_product p ON oi.product_id = p.id SET oi.unit_price = COALESCE(p.price, 0) WHERE oi.unit_price IS NULL` — 기존 row 를 product 현재가로 backfill. product 가 hard-delete 된 row 는 `0` sentinel 로 fallback.
3. `ALTER TABLE tbl_order_item MODIFY COLUMN unit_price INT NOT NULL` — NOT NULL 전환.

### Backfill 한계

- 기존 row 의 `unit_price` 는 "migration 적용 시점의 product 현재가 (또는 product 부재 시 0)" 로 채워진다. 결제 시점 가격이 아니다 (애초에 결제 시점 가격이 휘발한 상태였기에 정확한 값은 불가능).
- product 가 hard-delete 된 row 는 `0` sentinel 로 fallback 되어 migration 안정성을 보장한다. `0` 은 후속 사용처에서 이상치로 잡혀 데이터 무결성 위반이 가시화된다.
- 본 결정은 task adr 결정 2 에 명문화되어 있다.

### Lock / Deadlock

- `product_id` 의 FK 는 PR #203 (V4) 에서 제거됐다. JOIN UPDATE 는 일반 nested loop 으로 수행되어 deadlock 위험이 낮다.
- `tbl_order_item` 의 row 수가 운영 환경에서 충분히 적어 단일 ALTER 가 가능하다고 가정한다 (배포 시 별도 lock 분석은 본 task 범위 밖).

### 롤백

- V5 를 롤백하려면 V6 로 reverse migration (DROP COLUMN) 을 추가해야 한다. Flyway 정책상 V5 자체를 immutable 로 둔다.

## 신규 / 변경 테이블 요약

- 신규 테이블: 없음.
- 변경 테이블: `tbl_order_item` (컬럼 1건 추가).
