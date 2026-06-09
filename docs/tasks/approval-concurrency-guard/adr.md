# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.

---

## ADR-L1: reservation 동시 이중 use 가드를 @Version 낙관적 락으로 구현한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- `PaymentApprovalRecordService.create()`의 `reservation.use()`는 메모리 상태 검사만 한다. 같은 예약(merchantPayKey)에 **다른 pgPaymentId** 승인 2건이 USE 커밋 전 경합하면 둘 다 RESERVED로 읽고 둘 다 `use()`+save+payment 생성으로 통과한다 → REQUESTED payment 2건 → PG 청구 2건.
- 기존 unique 제약은 이 구멍을 막지 못한다. `uk_payment_reservation_reserved_key`는 "한 주문에 RESERVED 2개"만 막고, `tbl_payment`의 `(merchant_pay_key, provider, pg_payment_id, type)` unique는 pgPaymentId가 다르면 둘 다 INSERT를 허용한다. find-first 멱등은 **같은 pgPaymentId**만 흡수한다.
- 즉 "한 예약의 동시 이중 use"를 막는 장치가 없어, 예약 소비 전이에 동시성 가드가 필요하다.

### 고려한 대안

- **조건부 CAS** (`UPDATE ... SET status='USED', reserved_key=NULL WHERE id=? AND status='RESERVED'`의 영향 행 수로 판정): 락·버전 컬럼 없이 단일 원자 UPDATE로 가장 가볍다. 다만 도메인 `use()`의 "status·reserved_key 두 필드 동시 set" 캡슐화(ADR-3)가 SQL `SET`으로 이전되어 도메인 단위 테스트로 회귀를 잡지 못하고, "RESERVED일 때만 use" 전이 규칙이 도메인 메서드에서 사라진다.
- **비관적 락(FOR UPDATE)**: 단일 행 X-lock으로 직렬화. 읽기 시점부터 잠가 대기 구간이 길고, 단순 단일조건 전이에는 과하다.

### 결정 내용

- `PaymentReservation`에 `@Version`을 추가한다. 도메인 `use()`의 read-modify-write를 그대로 유지한다.
- `create()`의 `use()`+save 경로에서 진 쪽의 `ObjectOptimisticLockingFailureException`을 차단용 `PaymentException`으로 매핑한다. `create()`가 PG approve 호출보다 앞이므로 진 쪽은 PG 호출 전에 차단된다.

### 근거

- reservation 상태 전이 규칙("RESERVED일 때만")이 도메인 `use()`에 명시적으로 남아 ADR-3 캡슐화와 도메인 단위 테스트가 보존된다.
- 낙관적 락과 CAS는 lock 동작(UPDATE 시 행 X-lock으로 직렬화, 진 쪽 0 rows로 차단)과 정확성이 동등하다. 동등하다면 도메인 표현력을 살리는 `@Version`을 택한다. CAS의 인프라 단순성 이점은 이 단순 단일조건 전이에서는 표현력 손실 대비 이득이 작다.

### 결과

- 같은 예약·다른 pgPaymentId 동시 승인 시 한쪽만 진행하고 진 쪽은 PG approve 호출 전에 차단된다.
- `tbl_payment_reservation`에 `version` 컬럼 1개가 추가된다.
- 정합성의 최종 보루는 #230(`uk_payment_approved_order_key` + 보상)이 그대로 담당한다. 본 가드는 그 앞단에서 청구 도달 빈도를 낮추는 심층 방어다.

---

## ADR-L2: 승인 진입에 orderId 기준 성공결제 사전 차단을 추가한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- `approve()` 진입은 UNKNOWN만 `existsUnknownByOrderId`로 차단한다. 이미 성공한 APPROVE 결제가 있는 주문에 들어온 새 승인을 PG 호출 전에 막지 못해, 최종 보루(`uk_payment_approved_order_key`)까지 도달한 뒤 보상으로 처리된다.

### 고려한 대안

- 진입 차단 없이 최종 net(`uk_payment_approved_order_key` + 보상)에만 의존: 불필요한 PG 청구→취소 보상이 발생한다.

### 결정 내용

- `existsApprovedByOrderId(orderId)`(= APPROVE·SUCCEEDED 존재 EXISTS)를 `approve()` 진입의 UNKNOWN 차단과 나란히 추가한다. 존재하면 새 승인을 차단한다.

### 근거

- 기존 `existsUnknownByOrderId`와 동형 패턴이라 일관적이다. 첫 결제 성공 이후 들어온 새 승인을 PG 호출 전에 막아 보상 발생 빈도를 낮춘다.

### 결과

- 이미 성공 결제가 있는 주문의 새 승인이 진입 단계에서 차단된다. 정합성 자체는 여전히 #230이 최종 보장한다.

---

## ADR-L3: reservation 조회를 (memberId, merchantPayKey)로 단일화하고 예약 미발견을 PAYMENT_RESERVATION_NOT_FOUND로 응답한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 조회가 merchantPayKey 단독이라 member 검증이 별도 분기(`PAYMENT_MEMBER_MISMATCH`, 403)로 남아 있다. 남의 키가 존재하면 403이 반환되어 키 존재 여부가 노출된다.

### 고려한 대안

- 현행 유지(키로 조회 후 memberId 분기): member 불일치가 403으로 키 존재를 노출하고 분기가 흩어진다.

### 결정 내용

- `findByMemberIdAndMerchantPayKey(memberId, merchantPayKey)`로 조회를 단일화한다. 남의/없는 키 모두 예약 미발견으로 보아 전용 코드 `PAYMENT_RESERVATION_NOT_FOUND`로 응답하고 `PAYMENT_MEMBER_MISMATCH`를 제거한다. 예약 미발견을 결제(Payment) 미발견(`PAYMENT_NOT_FOUND`, PG/history 경로)과 분리하고 `PAYMENT_RESERVATION_ALREADY_USED`와 대칭을 이룬다.

### 근거

- 키 존재를 비노출(보안)하고 member 검증 분기를 조회 조건으로 흡수해 정리한다. 응답 의미 변화(403→404)는 의도된 것이다.

### 결과

- 남의/없는 키 승인 요청이 `PAYMENT_RESERVATION_NOT_FOUND`(404, "결제 예약을 찾을 수 없습니다")가 된다. `PAYMENT_MEMBER_MISMATCH` 에러코드가 제거된다. PG/history 경로의 `PAYMENT_NOT_FOUND`는 그대로 둔다. api-spec을 갱신한다.
