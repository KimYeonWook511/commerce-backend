# 태스크 DB 스키마

## 개요

도메인 엔티티 11개 기준 현 스키마를 **`V1__init.sql`로 베이스라인화**한다. 신규 테이블/컬럼 추가는 없다. 이번 작업의 핵심은 *어떤 스키마가 들어가느냐*가 아니라 *그 스키마가 Flyway 마이그레이션 스크립트로 명시적 관리 대상이 되느냐*이다.

PR review 단계에서 V1과 엔티티 사이의 silent mismatch들이 일괄 정리되어 ADR-018·ADR-023과 같은 결의 사례가 추가로 해소되었다. 상세는 `retrospective.md` "Review 단계에서 정리한 silent drift 사례들" 섹션 참조.

## 신규 테이블

- 없음. V1__init.sql은 현 운영(가동 전) 스키마와 동일한 상태로 작성된다.

## 변경 테이블

- 없음. 단, V1 생성 단계에서 dump 결과가 ADR-018(ENUM → VARCHAR)과 ADR-023(`tbl_payment_attempt` unique key 컬럼 길이 명시)를 모두 반영하는지 검증해야 한다.

## 인덱스

기존 엔티티에 정의된 인덱스가 V1에 모두 포함되는지 검증한다. 단일 unique 제약도 `uk_<table>_<columns>` 컨벤션으로 명시한다 (Review 단계에서 일괄 정리).

- `tbl_member`: `uk_member_email`
- `tbl_stock`: `uk_stock_product_id` (1:1 관계)
- `tbl_cart_item`: `uk_cart_item_member_product`
- `tbl_order`: `uk_order_member_idempotency`, `uk_order_merchant_pay_key`
- `tbl_payment`: `uk_payment_order_id`, `uk_payment_merchant_pay_key`, `uk_payment_pg_payment_id`
- `tbl_payment_attempt`: `uk_payment_attempt_merchant_pay_key_provider_payment_id_type` (컬럼 길이 명시 확인 필수)
- `tbl_outbox_event`: `uk_outbox_event_event_id`, `idx_outbox_event_type_status_next_retry_id`
- `tbl_processed_event`: `idx_processed_event_event_id_consumer_type`

## 데이터 무결성

- 모든 PK: `BIGINT AUTO_INCREMENT` (`GenerationType.IDENTITY`)
- 모든 ENUM 컬럼: `VARCHAR(...)` (ADR-018 / `@JdbcTypeCode(SqlTypes.VARCHAR)`). enum 유효성 검증을 위한 DB CHECK 제약은 두지 않는다 (ADR-025).
- multi-column unique constraint 컬럼: `@Column(length=...)` 명시 → V1 dump에서 InnoDB 한도 검증 (ADR-023)
- `@Version` 컬럼(`tbl_order`, `tbl_stock`, `tbl_cart_item`): `NOT NULL DEFAULT 0`. 엔티티에 `@Column(nullable = false)` 명시.
- `BaseTimeEntity` 상속 컬럼(`created_at`, `updated_at`): 모든 도메인 테이블에 존재, `NOT NULL`
- `tbl_outbox_event.payload`: `text` (MySQL Dialect의 `SqlTypes.LONGVARCHAR` 매핑 결과)
- ENGINE: InnoDB, CHARSET: utf8mb4, COLLATE: utf8mb4_0900_ai_ci

## 마이그레이션 고려사항

### V1__init.sql 생성 흐름

1. `mysql-data-local/` 삭제 (destructive, 사용자 사전 확인 완료)
2. `docker compose -f docker-compose.local.yml up -d mysql`
3. 임시 override로 부팅: `SPRING_FLYWAY_ENABLED=false`, `SPRING_JPA_HIBERNATE_DDL_AUTO=create`, `SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=never`
4. 부팅 안정 후 종료
5. `mysqldump --no-data --skip-comments --skip-add-drop-table --set-gtid-purged=OFF commerce_db`
6. dump 정리 (아래 체크리스트)
7. `src/main/resources/db/migration/V1__init.sql` 저장

### dump 정리 체크리스트

- [ ] 파일 상단/하단 `/*!40101 SET ... */` MySQL 셋팅 헤더 제거
- [ ] 각 `CREATE TABLE`의 `AUTO_INCREMENT=N` 옵션 제거 (시작값 1)
- [ ] 모든 테이블 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` 확인
- [ ] `DEFINER=...` 절이 있으면 제거
- [ ] Spring Batch 메타테이블(`BATCH_*`)이 포함되지 않음 확인
- [ ] ENUM 적용 컬럼이 MySQL ENUM이 아닌 VARCHAR로 떠 있음 확인 (ADR-018)
- [ ] 도메인 그룹 순서로 재배치: member → product → stock/stock_history → cart_item → order/order_item → payment/payment_attempt → outbox_event/processed_event
- [ ] `tbl_payment_attempt`의 `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`가 컬럼 길이 명시되어 InnoDB 한도 내 확인 (ADR-023, PR #179)
- [ ] PK 모두 `BIGINT AUTO_INCREMENT`
- [ ] `@Version` 컬럼이 `NOT NULL`로 떠 있음
- [ ] `BaseTimeEntity` 상속 컬럼(`created_at`, `updated_at`)이 모든 도메인 테이블에 존재

### 이후 마이그레이션 규칙

- 위치: `src/main/resources/db/migration/`
- 네이밍: `V{번호}__{snake_case_설명}.sql` (예: `V2__add_member_phone_column.sql`)
- 엔티티 변경 PR은 같은 PR에서 마이그레이션 스크립트도 함께 작성. 누락 시 `ddl-auto: validate`로 부팅 실패.
- `flyway_schema_history` 테이블은 Flyway 자체 관리. 직접 조작 금지.
- 기존 V 스크립트의 checksum이 바뀌면 Flyway가 부팅을 거부한다. 적용된 스크립트는 수정 대신 새 V로 보정.

### 베이스라인 전략

- baseline-on-migrate 사용하지 않음. 운영 DB 미가동 상태이므로 빈 DB에 V1 단일 적용으로 출발.
- 운영 가동 후 다른 환경(stage 등)을 추가할 때도 같은 V1부터 적용한다.
