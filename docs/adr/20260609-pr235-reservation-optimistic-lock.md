# reservation 동시 이중 use 가드를 `@Version` 낙관적 락으로 구현한다

- Status: accepted
- Date: 2026-06-09

## Context

#231. `create()`의 `reservation.use()`는 메모리 상태 검사만 하여, 같은 예약(merchantPayKey)에 다른 pgPaymentId 승인 2건이 USE 커밋 전 경합하면 둘 다 RESERVED로 읽고 통과 → REQUESTED payment 2건 → PG 청구 2건. `uk_payment_reservation_reserved_key`는 "한 주문 RESERVED 2개"만, `uk_payment_merchant_pay_key_provider_pg_payment_id_type`는 pgPaymentId가 다르면 둘 다 통과시켜 이 경합을 못 막는다. #230(`uk_payment_approved_order_key` 최종 보루)이 정합성을 보장하지만, PG 청구 도달 전 차단으로 보상 빈도를 낮추는 앞단 가드가 필요했다.

고려한 대안: (A) 조건부 CAS(`UPDATE ... WHERE status='RESERVED'` 영향 행 수) — 락·버전 컬럼 없이 단일 원자 UPDATE로 가볍지만, 도메인 `use()`의 두 필드 동시 set 캡슐화(결제 도메인 재설계의 NULL 트릭 캡슐화, → PR#205)가 SQL로 빠지고 "RESERVED일 때만" 전이 규칙이 도메인에서 사라져 단위 테스트 회귀 방어가 약해진다. (B) 비관적 락(FOR UPDATE) — 읽기부터 잠가 대기 구간이 길고 단순 단일조건 전이에 과하다.

낙관적 락과 CAS는 lock 동작(UPDATE 시 행 X-lock 직렬화, 진 쪽 0 rows)과 정확성이 동등하다. 동등하다면 도메인 표현력·캡슐화를 보존하는 `@Version`을 택한다. reservation은 단방향 상태 머신이라 `status`가 사실상 자연 version이라 CAS도 가능하지만, 도메인 `use()`를 그대로 살리는 쪽이 결제 도메인 재설계(→ PR#205)의 캡슐화·테스트와 정합적이다.

## Decision

`PaymentReservation`에 `version`(`@Version`)을 추가한다. 도메인 `use()`의 read-modify-write(status·reserved_key 동시 set, NULL 트릭 캡슐화(→ PR#205))는 유지한다. 승인 기록 경로는 예약 소비 전용 저장 메서드 `saveUsed`를 통해 `saveAndFlush`하고, 진 쪽의 `ObjectOptimisticLockingFailureException`을 adapter가 `PAYMENT_RESERVATION_ALREADY_USED` 도메인 예외로 번역한다(`saveApproved`와 같은 "조기 flush로 충돌을 메서드 안에서 확정" 패턴). `create()`가 PG approve 호출보다 앞이라 진 쪽은 PG 청구 전에 차단된다. USED 예약에 다른 pgPaymentId가 순차로 도착한 경우(승인 진입의 USED 분기)도 같은 `PAYMENT_RESERVATION_ALREADY_USED`로 일관 차단한다(과거 `PAYMENT_NOT_FOUND`에서 변경).

## Consequences

같은 예약·다른 pgPaymentId 동시 승인 시 한쪽만 진행하고 진 쪽은 PG 호출 전 `PAYMENT_RESERVATION_ALREADY_USED`로 차단된다. `tbl_payment_reservation.version` 컬럼이 추가된다(V7). cart의 낙관적 락이 retry로 흡수하는 것과 달리, 여기서는 진 쪽이 별개 결제(다른 pgPaymentId)이므로 재시도 없이 차단한다. 정합성 최종 보루는 #230이 그대로 담당하고 본 가드는 청구 도달 빈도를 낮추는 심층 방어다. 동시/순차 차단을 `NaverPayServiceConcurrencyTest`로 검증한다.

연계: 결제 도메인 재설계(→ PR#205), 보상 완료 가드 제거 결정(→ PR#233), #205, #230, #231, PR #235.
