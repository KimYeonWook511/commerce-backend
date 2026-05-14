# 기능 DB 스키마

## 개요

`tbl_order`에 `idempotency_key` 컬럼과 `(member_id, idempotency_key)` unique 제약을 추가한다.
별도 schema.sql이 없으며 Hibernate `ddl-auto`(local/prod: update, test: create-drop)로 관리된다.

## 변경 테이블

### tbl_order

| 컬럼 | 타입 | 변경 내용 |
|------|------|-----------|
| `idempotency_key` | VARCHAR(255) | 신규 추가, NULL 허용 |

**NULL 허용 이유**: 기존 데이터 호환 및 `OrderConcurrencyService` 경로(멱등성 없는 동시성 실험용)와의 호환

## 인덱스

| 인덱스명 | 대상 컬럼 | 목적 |
|----------|-----------|------|
| `uk_order_member_idempotency` | `(member_id, idempotency_key)` | 동일 회원 + 동일 key 중복 주문 방지 (최종 멱등성 보장) |

JPA 엔티티에서 `@Table(uniqueConstraints = @UniqueConstraint(...))` 로 선언한다.

## 데이터 무결성

- `member_id + idempotency_key` 복합 unique 제약으로 중복 주문 삽입 시 `DataIntegrityViolationException` 발생
- NULL 값은 unique 제약에서 제외됨 (MySQL 기준) — `OrderConcurrencyService` 경로의 NULL 값은 제약 대상이 아님

## 마이그레이션 고려사항

- `ddl-auto: update` 환경에서는 컬럼 추가와 unique 인덱스가 자동 적용된다.
- 기존 데이터의 `idempotency_key`는 NULL이므로 unique 제약 위반 없음.
- 운영 환경에서 추후 명시적 마이그레이션 도구로 전환 시 아래 DDL을 사용한다.

```sql
ALTER TABLE tbl_order
  ADD COLUMN idempotency_key VARCHAR(255) NULL,
  ADD UNIQUE KEY uk_order_member_idempotency (member_id, idempotency_key);
```
