# DB 스키마

이 태스크는 DB 스키마를 변경하지 않는다.

## 참고: unique 제약 현황 (catch 정책 결정 근거)

| 엔티티 | unique 제약 | 종류 |
|---|---|---|
| `tbl_member` | `email` | 비즈니스 (단일) |
| `tbl_payment_attempt` | `(paymentId, type)` | 비즈니스 (단일 복합) |
| `tbl_payment` | `merchantPayKey`, `order_id`, `pgPaymentId` | 비즈니스 (의미 통일) |
| `tbl_order` | `(member_id, idempotency_key)`, `orderNumber` | 비즈니스 + 기술적 (혼합) |
| `tbl_processed_event` | `(eventId, consumerType)` | 비즈니스 (단일 복합) |
