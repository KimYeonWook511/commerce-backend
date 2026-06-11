# Task DB 스키마

> 이 문서는 이번 Task가 추가·변경하는 스키마 **변경분(delta)**이다.
> 전체 스키마의 현재 진실은 루트 `docs/db-schema.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).
> 작업 종료 후 이 문서는 stale해질 수 있으며, 그대로 둔다(과거 변경 기록).

---

## 개요

- `tbl_payment`에 낙관 락용 `version` 컬럼 하나를 추가한다. 신규 테이블은 없다.

## 신규 테이블

- 없음

## 변경 테이블

- **변경 대상**: `tbl_payment`
- **변경 이유**: 같은 Payment 행을 동시에 read-modify-write로 전이하는 경합(`succeed` vs `fail` 등)에서 lost update를 막아야 한다. 다른 핵심 엔티티(Order/PaymentReservation/CartItem/Stock)는 모두 `@Version`을 갖는데 `Payment`만 없던 일관성 누락을 해소한다.
- **추가 컬럼**:
  - `version BIGINT NOT NULL DEFAULT 0` — JPA `@Version` 낙관 락 컬럼. 기존 행은 0으로 백필되고 이후 JPA가 쓰기 시점에 자동 증가한다. `PaymentReservation`에 version을 추가한 직전 마이그레이션과 동일 형태.

## 인덱스

- 추가하지 않는다. `version`은 PK(`id`) 기준 UPDATE의 WHERE 조건에 함께 쓰일 뿐 별도 조회 대상이 아니다.

## 데이터 무결성

- `version`은 JPA가 관리한다. 엔티티 UPDATE는 `WHERE id=? AND version=?`로 나가며, 동시 전이로 version이 어긋나면 0행 갱신 → `OptimisticLockException`. 수동 setter를 두지 않는다.
- escalation의 `escalatedAt` 기록도 이제 일반 엔티티 UPDATE(`escalate()` 도메인 메서드 + save) 경로를 타며, 멱등은 `@Version`이 보장한다(기존 조건부 UPDATE/영향 행 수 방식 폐기). `escalatedAt` 컬럼 자체는 직전 Task에서 추가됐고 이번에 변경하지 않는다.
- `tbl_payment` append-only 원칙 유지: 행 삭제 없이 상태 전이(UPDATE)만.

## 마이그레이션 고려사항

- 파일: `src/main/resources/db/migration/V9__add_payment_version.sql` (현재 최신 V8 다음).
- `ALTER TABLE tbl_payment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`
- `NOT NULL DEFAULT 0`이라 기존 행 백필 불필요(기존 행은 version=0에서 시작). 롤백은 컬럼 DROP으로 안전.
