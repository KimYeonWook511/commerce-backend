# 결제 도메인을 재설계해 Order↔Payment 경계를 분리하고 RESERVE를 별도 거주지에 둔다

- Status: accepted — PR#305가 **부분 대체**. 아래 Decision 중 **두 테이블 분리·예약 슬롯·예약 재사용 정책**은 예약 테이블이 결제 행으로 흡수되며 대체됐다. 나머지(식별자 참조, 외부 호출을 트랜잭션 밖에 두는 경계)는 유지된다.
- Date: 2026-06-05

## Context

결제 도메인 재설계에서 여러 설계안을 검토했고 그중 B안(두 테이블 분리안)을 채택했다. 대안 비교를 포함한 상세는 task 문서 `docs/tasks/payment-order-redesign/adr.md`(해당 task 내부 기록 ADR-1 ~ ADR-10)에 있다.

## Decision

1. **두 테이블 분리** — `tbl_payment_reservation` (상태 `{RESERVED, USED, EXPIRED}`, 결제창 준비물, RESERVED → USED/EXPIRED 한 번 전이) + `tbl_payment` (타입 `{APPROVE, CANCEL}`, PG 사건 append-only)
2. **merchantPayKey 책임 이동** — Order.merchantPayKey / assignMerchantPayKey / findByMerchantPayKey* 전량 제거. merchantPayKey 발급·저장 책임이 PaymentReservation으로 이동
3. **NULL 트릭 partial unique** — MySQL InnoDB의 partial unique index 미지원 → `uk_payment_approved_order_key (approved_order_key NULL)` + `uk_payment_reservation_reserved_key (reserved_key NULL)`로 대체. 조건 불충족 행은 NULL로 두어 unique 제외. 두 컬럼 모두 도메인 메서드(`succeed`, `markUsed`, `markExpired`) 안에서 상태 변경과 *같은 UPDATE*에 묶어 캡슐화 강제. 만료/무효 예약은 reserve 진입 시 `markExpired`로 reservedKey를 회수해 재예약 허용
4. **완료 판단 = EXISTS** — `(성공 APPROVE 존재) AND (무효화 성공 CANCEL 부재)`. 마지막 행 기반 판단 금지. 현재는 `existsApproveSucceeded(merchantPayKey)`로 단순 구현
5. **UNKNOWN 마킹** — PG 호출 timeout / DB 반영 실패 시 `Payment.markUnknown` 흔적 보존. UNKNOWN 행 있는 주문에 reserve/approve 차단 (`PAYMENT_RESULT_PENDING` 409). 해소는 후속 task `PaymentReconciliationService`
6. **API rename** — `POST /payments/ready` → `POST /payments/reserve` (frontend 미개발이라 호환 깨도 무방)
7. **멱등 redirect 흡수** — 같은 merchantPayKey의 redirect 중복은 차단이 아닌 *기존 결제 결과 200 응답*으로 흡수
8. **reserve 재사용 정책** — `(status=RESERVED ∧ expiresAt>now ∧ provider 일치 ∧ memberId 일치 ∧ amount 일치)` 조건으로 기존 Reservation 재사용. 만료·amount mismatch 시 새 Reservation 발급 (amount UPDATE 금지)
9. **외부 PG 호출 경계 유지** — PG 호출은 트랜잭션 밖, payment+order DB 쓰기는 한 트랜잭션 안 (회원가입 트랜잭션 분리에서 정한 기존 정책(→ PR#97) 유지)
10. **Flyway V6 마이그레이션** — `tbl_payment` (구 성공 1:1) DROP → `tbl_payment_attempt` → `tbl_payment` RENAME + 컬럼 정리 + `tbl_payment_reservation` CREATE + `tbl_order` merchant_pay_key 관련 DROP

## Consequences

이 결정 위에 후속 결정들이 쌓였다 — 멱등 재요청 amount mismatch 처리(→ PR#101, amount mismatch → 새 Reservation), 보상 진행 여부 판단(→ PR#118, `existsApproveSucceeded` 구현 갱신), 보상 정책 책임 배치(→ PR#125, `compensateDuplicateApproval` 추가).
