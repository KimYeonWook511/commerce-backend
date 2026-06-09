# Task DB 스키마

> 이 문서는 이번 Task가 추가·변경하는 스키마 **변경분(delta)**이다.
> 전체 스키마의 현재 진실은 루트 `docs/db-schema.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).
> 실제 DDL은 Flyway V스크립트가 단일 출처다.

---

## 개요

- `tbl_payment_reservation`에 낙관적 락용 `version` 컬럼 1개를 추가한다. 그 외 테이블·인덱스·제약 변경은 없다.

## 신규 테이블

- 없음.

## 변경 테이블

- `tbl_payment_reservation`
  - 신규 컬럼 `version BIGINT NOT NULL DEFAULT 0`.
  - 변경 이유: 같은 예약의 동시 이중 use를 낙관적 락(`@Version`)으로 감지하기 위함(ADR-L1).

## 인덱스

- 없음. `version`은 낙관적 락 비교 컬럼이라 인덱스가 필요하지 않다.

## 데이터 무결성

- 기존 행은 `DEFAULT 0`으로 채워진다. 이후 도메인 쓰기에서 JPA가 version을 자동 증가시킨다.
- 이중 PG 청구에 대한 최종 무결성 보장은 기존 `uk_payment_approved_order_key`(#230)가 그대로 담당한다. `version`은 그 앞단 동시 이중 use 차단용이다.

## 마이그레이션 고려사항

- Flyway `V7__add_payment_reservation_version.sql`로 컬럼을 추가한다.
- 배포 순서: 컬럼 추가가 먼저 적용되어야 `@Version` 매핑이 동작한다. `DEFAULT 0`이라 백필은 불필요하다.
- 롤백: `version` 컬럼 DROP.
