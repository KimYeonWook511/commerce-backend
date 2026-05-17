# 기능 DB 스키마

## 스키마 변경 없음

이번 변경은 `PaymentAttemptService` 내부 정책이며 DB 스키마 변경이 없다.

`tbl_payment_attempt`의 컬럼, 인덱스, 제약 조건은 모두 기존 그대로 유지된다.
unique 제약 `uk_payment_attempt_merchant_pay_key_provider_payment_id_type (merchant_pay_key, provider, payment_id, type)`를 활용해 amount 검증이 이뤄진다.
