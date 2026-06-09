# Task DB 스키마

> 이 문서는 이번 Task가 추가·변경하는 스키마 **변경분(delta)**이다.
> 전체 스키마의 현재 진실은 루트 `docs/db-schema.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).

---

## 개요

- `tbl_payment.status`의 허용 값에 `MANUAL_REVIEW`를 추가한다. 컬럼 자체(타입·길이·제약)는 변경하지 않는다.

## 신규 테이블

- 없음

## 변경 테이블

- `tbl_payment`
  - 변경 대상: `status` 컬럼의 애플리케이션 허용 값 (`PaymentStatus` enum)
  - 변경 이유: 대사 자동 처리 상한 초과 건을 운영자 확인 대상으로 승급하는 종착 상태(`MANUAL_REVIEW`)를 일급 표현 (ADR-L5)
  - `status`는 `@Enumerated(STRING)` + `VARCHAR(32)`이므로 **DDL 변경 없이** 저장 가능한 값만 늘어난다(`MANUAL_REVIEW` = 13자).

## 인덱스

- 변경 없음. stale 스캔은 기존 `status` + 시각 컬럼(`responded_at`/`created_at`) 기반 조회를 사용한다. 운영 데이터량 증가 시 대사 스캔용 인덱스는 후속에서 검토한다(이번 범위 밖).

## 데이터 무결성

- `uk_payment_approved_order_key`(기존)가 이중 SUCCEEDED를 차단해 대사 멱등성을 보장한다. 추가 제약 없음.

## 마이그레이션 고려사항

- enum 값 추가는 컬럼 DDL을 바꾸지 않으므로 별도 Flyway 스크립트가 필요 없다(저장 값만 확장).
- 기존 데이터는 영향 없음. 롤백 시 `MANUAL_REVIEW` 값을 쓰는 행이 생기기 전이라면 코드 되돌림만으로 충분하다.
