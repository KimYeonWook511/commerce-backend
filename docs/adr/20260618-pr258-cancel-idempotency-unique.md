# CANCEL 생성 멱등은 기존 (merchantPayKey, provider, pgPaymentId, type) unique로 하드 보장된다

- Status: superseded — PR#305가 대체. 환불 중복 차단이 `(payment_id, requester, idempotency_key)` 유일 제약으로 옮겨졌다. 예약과 결제사 번호에 기대던 네 열 제약은 그 테이블이 사라지며 함께 없어진다.
- Date: 2026-06-18

## Context

4-col unique `uk_payment_merchant_pay_key_provider_pg_payment_id_type`의 `type`에 CANCEL도 포함되어 pgPaymentId당 CANCEL 행이 하나로 강제된다(APPROVE 행과는 type이 달라 충돌하지 않음). 전체취소 스코프에선 한 결제당 CANCEL이 하나(전액)라 이 unique가 정확한 멱등 키다.

## Decision

사용자 취소 환불의 CANCEL 생성 멱등은 기존 unique `uk_payment_merchant_pay_key_provider_pg_payment_id_type`가 하드로 보장한다(`getOrCreate` find + unique → 동시 생성 시 한쪽이 unique 위반 안전망 500). order FOR UPDATE 잠금은 보조 직렬화다.

## Consequences

**테스트 parity**: 이 unique는 Flyway(prod/local)엔 있으나 Payment 엔티티 `@Table`엔 없어 test(H2 `create-drop`)에는 제약이 없었다. 멱등을 H2 테스트로 검증하려 엔티티에 미러링했다(스키마 변경 아님, prod는 `validate`라 무해).

부분취소(한 결제에 CANCEL 여럿, 금액만 다름)가 오면 이 unique로 표현 불가다. 그때 취소 요청 단위 고유 키 + "Σ취소 ≤ 승인액" 한도 검증(잠금 하)으로 재설계한다(범위 밖).

관련: payment-order-redesign task(4-col unique·NULL 트릭).
