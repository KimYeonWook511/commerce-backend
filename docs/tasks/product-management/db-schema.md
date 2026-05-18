# 기능 DB 스키마

## 개요

- 기존 `tbl_product`에 상품 관리 필드를 추가한다.
- 신규 테이블은 없다.

## 신규 테이블

- 없음

## 변경 테이블

### `tbl_product`

변경 이유:
- 상품 설명, 이미지 URL, 판매 상태, soft delete 상태를 저장하기 위함이다.

추가 컬럼:
- `description`
- `image_url`
- `status`
- `deleted_at`

기존 컬럼:
- `id (PK)`
- `name`
- `price`

## 인덱스

- 별도 신규 인덱스는 이번 feature에서 추가하지 않는다.
- 공개 조회 성능 개선이 필요해지면 `status`, `deleted_at`, `created_at` 조합 인덱스를 후속으로 검토한다.

## 데이터 무결성

- `name`은 null이 아니어야 한다.
- `price`는 null이 아니며 0보다 큰 값이어야 한다.
- `status`는 null이 아니어야 한다.
- 삭제된 상품 row는 주문 이력 보존을 위해 유지한다.

## 마이그레이션 고려사항

- 기존 상품 row의 `status` 기본값은 `ON_SALE`로 본다.
- 기존 상품 row의 `deleted_at`은 null로 본다.
- 실제 DB 마이그레이션 도구는 현재 레포지토리에 없으므로 JPA 엔티티와 문서를 먼저 동기화한다.
