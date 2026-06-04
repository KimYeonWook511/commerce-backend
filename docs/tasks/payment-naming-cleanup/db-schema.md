# 태스크 DB 스키마

## 개요

- 변경 없음. 순수 네이밍/구조 정리 작업이라 테이블·컬럼·인덱스·제약조건을 변경하지 않는다.

## 신규 테이블

- 해당 없음.

## 변경 테이블

- 해당 없음.

## 인덱스

- 해당 없음. NULL 트릭 unique 인덱스(`uk_payment_approved_order_key`, `uk_payment_reservation_reserved_key`)는 그대로 유지된다.

## 데이터 무결성

- 변경 없음. 에러코드 enum 식별자 rename은 `@Enumerated(EnumType.STRING)` 으로 저장되는 `PaymentStatus`/`PaymentReservationStatus` 등 상태 값과 무관하다 (PaymentErrorCode 는 영속 대상이 아님).

## 마이그레이션 고려사항

- 신규 마이그레이션 없음. 기존 migration 파일(V1/V3/V6 등)은 적용된 이력이라 수정하지 않는다.
