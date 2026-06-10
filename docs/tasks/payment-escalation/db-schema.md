# Task DB 스키마

> 이 문서는 이번 Task가 추가·변경하는 스키마 **변경분(delta)**이다.
> 전체 스키마의 현재 진실은 루트 `docs/db-schema.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).
> 작업 종료 후 이 문서는 stale해질 수 있으며, 그대로 둔다(과거 변경 기록).

---

## 개요

- `tbl_payment`에 escalation(운영 위임) 시각을 담는 `escalated_at` 컬럼 하나를 추가한다. 신규 테이블은 없다.

## 신규 테이블

- 없음

## 변경 테이블

- **변경 대상**: `tbl_payment`
- **변경 이유**: 6시간 초과 UNKNOWN/REQUESTED APPROVE 결제를 운영자에게 통지하고 자동 대사에서 영구 제외하려면, "이미 운영자에게 위임했다"는 종착 표시가 필요하다. status를 늘리지 않고(ADR-044 준수) 직교 축으로 표현한다(ADR-L1).
- **추가 컬럼**:
  - `escalated_at DATETIME(6) NULL` — escalation(운영 위임) 시각. `NULL`이면 미escalation. `status`와 무관한 직교 필드. set은 조건부 UPDATE(`WHERE escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED)`)로만 하며, status는 바꾸지 않는다.

## 인덱스

- 이번에는 추가하지 않는다. escalation 스캔(`type=APPROVE AND escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED) AND 시각 < 6시간前`)이 새로 생기지만, 6시간 초과 미처리 건은 소수이고 `escalated_at` 기록 후 즉시 스캔에서 빠져 누적되지 않는다. 데이터량이 커지면 `(status, escalated_at)` 복합 인덱스를 후속에서 검토한다.

## 데이터 무결성

- `escalated_at`은 조건부 UPDATE(`SET escalated_at=:now WHERE id=:id AND escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED)`)로만 set한다. 영향 행 수 1이 "escalate 주체"를 결정한다(동시 race에서도 1행만 갱신 허용). 우회 setter 금지.
- `tbl_payment` append-only 원칙 유지: 행 삭제 없이 상태 전이(UPDATE)만. `escalated_at` 기록은 그 UPDATE의 일부다.
- 멱등: 한 행은 `escalated_at`이 한 번만 채워진다. `Payment`에 `@Version`이 없어 메모리 객체 가드로는 동시 race를 막지 못하므로, DB 레벨 조건부 UPDATE의 `IS NULL` WHERE + 영향 행 수로 보장한다.

## 마이그레이션 고려사항

- 파일: `src/main/resources/db/migration/V8__add_payment_escalated_at.sql` (현재 최신 V7 다음).
- `ALTER TABLE tbl_payment ADD COLUMN escalated_at DATETIME(6) NULL;`
- nullable 컬럼 추가라 기존 행 백필 불필요(기존 행은 `NULL` = 미escalation). 롤백은 컬럼 DROP으로 안전.
