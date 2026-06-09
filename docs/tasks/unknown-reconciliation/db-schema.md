# Task DB 스키마

> 이 문서는 이번 Task가 추가·변경하는 스키마 **변경분(delta)**이다.
> 전체 스키마의 현재 진실은 루트 `docs/db-schema.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).

---

## 개요

- 이 Task는 DB 스키마(테이블·컬럼·인덱스·제약)를 **변경하지 않는다.**
- 초기 설계에서 `tbl_payment.status`에 `MANUAL_REVIEW` enum 값 추가를 검토했으나, ADR-039(보상된 APPROVE는 `FAILED`+failCode로 유지, 새 상태 도입 기각)와 충돌해 **철회**했다(ADR-L5). `PaymentStatus`는 `REQUESTED`/`SUCCEEDED`/`FAILED`/`UNKNOWN` 4값을 그대로 유지한다.

## 신규 테이블

- 없음

## 변경 테이블

- 없음

## 인덱스

- 변경 없음. 대사 스캔(`1분~6시간` 윈도우)은 기존 `status` + 시각 컬럼(`responded_at`/`created_at`) 기반 조회를 사용한다. 운영 데이터량 증가 시 대사 스캔용 인덱스는 후속(#239)에서 검토한다.

## 데이터 무결성

- `uk_payment_approved_order_key`(기존)가 이중 SUCCEEDED를 차단해 대사 멱등성을 보장한다. 추가 제약 없음.

## 마이그레이션 고려사항

- 스키마 변경이 없으므로 Flyway 스크립트가 필요 없다.
- `PaymentFailCode`에 보상용 코드(`ORDER_CANCELED` 등)를 추가하지만, failCode는 `@Enumerated(STRING)` + 기존 컬럼에 저장되는 애플리케이션 enum이라 DDL 변경이 없다(저장 값만 확장).
