# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `approval-concurrency-guard`

## 배경

- #227(PR #228) / #230과 같은 발견 맥락이다.
- #230이 "중복이 발생한 뒤(성공 시점) 보상 정합성"을 다룬다면, 본 Task는 "**중복이 PG 청구까지 도달하기 전에 막기**"를 다룬다.
- 진짜 동시 race는 100% 막을 수 없어 #230(최후의 net)이 필수다. 본 Task는 도달 빈도를 낮추고 원천 차단하기 위해 진입·예약 단계 가드를 보강한다.

## 문제

1. **reservation 사용 전이에 동시성 가드 부재**
   - `PaymentReservation`에 `@Version`/행 락이 없고, `PaymentApprovalRecordService.create()`의 `reservation.use()`는 메모리 상태 검사만 한다.
   - 같은 merchantPayKey에 다른 pgPaymentId 승인 2건이 USE 커밋 전 경합하면 둘 다 RESERVED로 읽고 둘 다 `use()`+save+payment 생성 → **REQUESTED payment 2건 → PG 청구 2건**.
   - `uk_payment_reservation_reserved_key`(NULL trick)는 "한 주문에 RESERVED 예약 2개"만 막지, **한 예약의 동시 이중 use**는 못 막는다.
   - `tbl_payment`의 `uk_payment_merchant_pay_key_provider_pg_payment_id_type`는 pgPaymentId가 다르면 둘 다 INSERT를 통과시키므로 다른 pgPaymentId 2건은 막지 못한다. `create()`의 find-first는 **같은 pgPaymentId**만 멱등 흡수한다.
2. **진입 사전 차단 부재**
   - `approve()` 진입에서 orderId 기준 "이미 성공한 APPROVE 결제 있음" 차단이 없다(현재 UNKNOWN만 `existsUnknownByOrderId`로 차단). 첫 결제 커밋 이후 들어온 새 승인을 PG 호출 전에 막지 못하고 최종 보루(`uk_payment_approved_order_key`)까지 가서 보상으로 처리된다.
3. **reservation 조회가 merchantPayKey 단독**
   - 조회가 merchantPayKey 단독이라 member 검증이 별도 분기(`PAYMENT_MEMBER_MISMATCH`, 403)로 남아 있고, 남의 키 존재 시 403이 반환되어 키 존재가 노출된다.

## 목표

- 진입·예약 단계 가드로 이중 PG 청구가 PG 청구까지 도달하는 빈도를 원천적으로 낮춘다.
- 정합성의 최종 보루는 #230(`uk_payment_approved_order_key` + 보상)이 그대로 담당한다. 본 Task는 그 앞단의 심층 방어다.

## 범위

- 포함 범위
  - reservation 동시 이중 use 가드: `PaymentReservation`에 `@Version` 도입, `create()`가 진 쪽의 낙관적 락 충돌을 PG 호출 전에 차단.
  - 진입 사전 차단: `existsApprovedByOrderId`(= APPROVE·SUCCEEDED 존재) 추가, `approve()` 진입에서 새 승인 차단.
  - reservation 조회를 (memberId, merchantPayKey)로 단일화, `PAYMENT_MEMBER_MISMATCH` 분기·에러코드 제거(남의 키 → `NOT_FOUND`).
  - 관련 단위·통합·동시성 테스트.
- 제외 범위
  - #230의 보상 경로 자체. 최후의 net은 현행 그대로 둔다. 본 Task는 그 앞단 빈도 저감·심층 방어까지만 책임진다.
  - 카카오페이 승인 경로(미구현).
  - postprocess 배치 reconcile 정책(#221/#208 범위).

## 주요 시나리오

- 같은 reservation·다른 pgPaymentId **동시 승인** → 한쪽만 payment 생성·PG 호출되고, 진 쪽은 낙관적 락 충돌로 **PG 호출 전 차단**된다.
- 이미 성공 결제가 있는 주문에 **새 승인**이 진입 단계에서 차단된다.
- 남의 merchantPayKey로 승인 요청 → `PAYMENT_NOT_FOUND`(키 존재 비노출).

## 요구사항

- `PaymentReservation`에 `@Version`을 추가하고 도메인 `use()`는 그대로 둔다.
- `PaymentApprovalRecordService.create()`의 `use()`+save 경로에서 진 쪽 `ObjectOptimisticLockingFailureException`을 차단용 `PaymentException`으로 매핑한다. `create()`가 PG approve 호출보다 앞이라 진 쪽은 PG 호출 전에 차단되어야 한다.
- `approve()` 진입에 `existsApprovedByOrderId(orderId)` 차단을 기존 UNKNOWN 차단과 나란히 추가한다.
- reservation 조회를 (memberId, merchantPayKey)로 단일화하고 `PAYMENT_MEMBER_MISMATCH`를 제거한다.
- 동시성 테스트로 "같은 reservation·다른 pgPaymentId 동시 승인 시 한쪽만 payment 생성·PG 호출, 나머지는 PG 호출 전 차단"을 검증한다.

## 제약사항

- 진 쪽 차단은 반드시 PG approve 호출보다 **앞**에서 일어나야 한다.
- 정합성의 최종 보장은 #230을 유지한다. 본 Task는 그것을 대체하지 않고 빈도 저감·심층 방어를 더한다.
