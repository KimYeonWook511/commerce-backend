# DB Schema — PAID 주문 취소·환불

## 스키마 변경 없음

이 작업은 새 테이블·컬럼·인덱스·제약을 추가하지 않는다. 기존 구조를 그대로 사용한다.

- **주문 취소**: `tbl_order.status`의 기존 `CANCELED` 값을 사용한다. PAID 허용은 도메인
  전이 규칙 변경일 뿐 컬럼 변경이 아니다.
- **환불**: 기존 `tbl_payment`(append-only)에 기존 `PaymentType.CANCEL` 레코드를 쌓는다.
  새 컬럼 없이 보상 경로가 쓰던 CANCEL 레코드 구조를 그대로 재사용한다.
- **환불 대상 조회**: orderId로 SUCCEEDED APPROVE 결제를 가져오는 조회를 추가하지만,
  이는 쿼리 추가일 뿐 스키마 변경이 아니다(기존 컬럼·인덱스 사용).
- **CANCEL 대사 스캔**: `type=CANCEL ∧ status∈{REQUESTED,UNKNOWN}`인 stale CANCEL을 긁는 조회를
  추가한다. 기존 APPROVE 대사 스캔과 같은 컬럼(type·status·createdAt·respondedAt)을 쓰며 새 인덱스·
  컬럼을 추가하지 않는다.
- **CANCEL 멱등**: 새 UNIQUE를 추가하지 않는다. 기존
  `uk_payment_merchant_pay_key_provider_pg_payment_id_type`의 `type`에 CANCEL이 포함돼 pgPaymentId당
  CANCEL 하나가 이미 하드 보장된다(ADR-L5). 다만 이 unique가 Payment 엔티티 `@Table`엔 선언돼 있지
  않아 H2 테스트 스키마엔 없으므로, 엔티티에 미러링한다(스키마 변경 아님, step1).

DDL의 단일 출처는 Flyway V스크립트이며, 이번 작업은 새 V스크립트를 추가하지 않는다.
