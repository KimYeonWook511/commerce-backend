# 태스크 DB 스키마

## 개요

이 태스크는 DB 스키마를 변경하지 않는다.

## 변경 없음

- `tbl_payment` 스키마 유지
- `tbl_payment_attempt` 스키마 유지
- 신규 테이블 없음
- 인덱스 변경 없음
- 마이그레이션 없음

## 비고

ADR-1에서 Payment 존재 여부 판단을 위해 `paymentRepository.findByMerchantPayKey(merchantPayKey)`를 사용한다. 이 컬럼에는 이미 unique 제약과 인덱스가 있으므로 추가 인덱스 불필요하다.
