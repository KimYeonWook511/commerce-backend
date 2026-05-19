# 태스크 DB Schema

## 변경 사항

이번 작업은 DB 스키마 변경이 없다.

`tbl_payment_attempt` 테이블의 기존 컬럼(`status`, `type`, `fail_code`, `fail_detail`, `responded_at`)과 unique 제약(`uk_payment_attempt_merchant_pay_key_provider_payment_id_type`)은 그대로 유지된다.

검증 로직은 Java 도메인 모델 계층에서만 추가된다.
