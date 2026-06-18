# payment-order-decouple — DB 스키마

## 변경 없음

이 task는 내부 구조 리팩터다. 테이블·컬럼·인덱스·제약을 변경하지 않는다.

- 기존 `uk_payment_approved_order_key`(주문당 SUCCEEDED APPROVE 1개) 제약은 그대로 활용한다 —
  이 제약이 PAID 성공-주체 분기의 도달 불가능성(ADR-L3)을 보장하는 근거다.
- Flyway 마이그레이션 스크립트를 추가하지 않는다.
