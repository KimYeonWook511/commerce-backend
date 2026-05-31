# 태스크 DB 스키마

## 개요

- `tbl_payment_attempt`의 4개 컬럼 길이를 명시한다. 신규 테이블/컬럼 추가 없음.

## 신규 테이블

- 해당 없음.

## 변경 테이블

### `tbl_payment_attempt`

- `merchant_pay_key`: 기본값 → `VARCHAR(64)`
- `payment_id`: 기본값 → `VARCHAR(64)`
- `provider`: 기본값 → `VARCHAR(32)`
- `type`: 기본값 → `VARCHAR(32)`

변경 이유: 위 4개 컬럼은 multi-column unique constraint `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`를 구성한다. 기본값(`VARCHAR(255)` × 4 × utf8mb4 4byte = 4080 bytes)이 InnoDB unique key 한도 3072 bytes를 초과해 schema 생성이 실패하고 있었다.

## 인덱스

- `uk_payment_attempt_merchant_pay_key_provider_payment_id_type (merchant_pay_key, provider, payment_id, type) UNIQUE` — 기존 정의 유지. 본 fix로 schema에 정상 적용된다.

## 데이터 무결성

- 위 unique constraint이 적용되어 동일 `(merchantPayKey, provider, paymentId, type)`에 대해 `PaymentAttempt` row가 한 건만 존재함이 DB에서 보장된다.
- ADR-011 (find-first 패턴)의 race window 안전망이 이 unique constraint에 의존한다.

## 마이그레이션 고려사항

- 운영 미가동 상태이므로 prod schema 변경 절차 없음.
- 로컬 MySQL 볼륨(`./mysql-data-local`)은 wipe 후 ddl-auto로 재생성한다.
- 추후 Flyway 도입 시 본 fix 시점의 컬럼 길이를 마이그레이션 baseline에 반영한다.
- 운영 데이터가 없으므로 length 축소로 인한 truncation 위험 없음. 추후 운영 시점에는 사전에 `MAX(CHAR_LENGTH(...))` 점검 절차가 필요하다.
