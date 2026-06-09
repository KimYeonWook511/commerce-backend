# 회고록: approval-concurrency-guard

## 1. 작업 요약

이중 PG 청구가 PG 청구까지 도달하기 **전에** 막는 진입·예약 단계 가드를 보강했다(#231). 정합성의 최종 보루는 #230(`uk_payment_approved_order_key` + 보상)이 그대로 담당하고, 본 작업은 그 앞단의 심층 방어다.

- **예약 동시 이중 use 가드(ADR-036)**: `PaymentReservation`에 `@Version`을 추가하고, 승인 기록 경로를 예약 소비 전용 저장 메서드 `saveUsed`로 통과시켜 진 쪽의 `ObjectOptimisticLockingFailureException`을 adapter가 `PAYMENT_RESERVATION_ALREADY_USED`로 번역한다. `create()`가 PG 호출보다 앞이라 진 쪽은 PG 청구 전에 차단된다.
- **진입 사전 차단(ADR-037)**: `existsApprovedByOrderId`로 이미 성공 결제가 있는 주문의 새 승인을 PG 호출 전에 `PAYMENT_DUPLICATE`로 차단한다. 기존 UNKNOWN 차단과 동형이다.
- **조회 단일화(ADR-038)**: 예약 역조회를 `(memberId, merchantPayKey)`로 단일화하고 `PAYMENT_MEMBER_MISMATCH`(403)를 제거해 남의/없는 키를 `PAYMENT_NOT_FOUND`(404)로 흡수한다(키 존재 비노출).

단일 phase 3 step(reservation-version-guard, approved-order-entry-guard, reservation-lookup-unification)으로 구현했고, PR #235 리뷰(Gemini)에서 USED 예약 재사용 에러코드를 `PAYMENT_RESERVATION_ALREADY_USED`로 일관화하면서 진입 가드 도입이 만든 동시성 테스트 회귀까지 함께 정리했다.

---

## 2. 설계 결정

자세한 본문은 [task ADR](./adr.md)(staging L1~L3) 및 루트 ADR-036~038 참조.

| ADR | 핵심 결정 |
|---|---|
| ADR-036 (L1) | 예약 동시 이중 use 가드를 `@Version` 낙관적 락으로 구현. 도메인 `use()`의 NULL 트릭 캡슐화(ADR-026)를 보존. `saveUsed`가 `saveAndFlush` 조기 flush로 충돌을 PG 호출 전 확정·번역. |
| ADR-037 (L2) | 승인 진입에 `existsApprovedByOrderId` 사전 차단 추가. USED 분기 **이후**에 배치해 멱등 응답을 가로채지 않음. |
| ADR-038 (L3) | 예약 조회를 `(memberId, merchantPayKey)`로 단일화, 남의 키 → `PAYMENT_NOT_FOUND`(존재 비노출). `PAYMENT_MEMBER_MISMATCH` 제거. |

---

## 3. 핵심 발견과 교훈

### CAS vs @Version — 정확성이 동등하면 도메인 정합성으로 가른다

가드 방식 결정에 가장 많은 논의가 들어갔다. 결론은 **둘의 lock 동작과 정확성이 동등**하다는 것이었다 — 낙관적 락도 "읽기 lock-free, UPDATE 시 행 X-lock으로 직렬화, 진 쪽 0 rows로 차단"으로 CAS와 같다. 차이는 감지 기준(version vs `WHERE status='RESERVED'`)과 예외 처리(JPA 자동 vs affected 직접 체크)뿐이다.

핵심 통찰은 **reservation이 `RESERVED → USED/EXPIRED` 단방향 상태 머신**이라 `status`가 사실상 자연 version 역할을 한다는 점이다. 그래서 CAS도 충분히 타당했지만, 도메인 `use()`의 "status·reserved_key 두 필드 동시 set" 캡슐화(ADR-026)와 단위 테스트를 그대로 살리는 `@Version`을 택했다. "동등하면 도메인 표현력을 보존하는 쪽"이 결정 기준이었다.

> 만료 후처리(배치)를 1급으로 두면 CAS(use·만료 bulk를 같은 조건부 UPDATE로 통일)가 더 일관적이라는 반론도 검토했다. 현재는 만료가 reserve 진입 lazy `expire()` 단일 행뿐이라 그 부담이 없어 `@Version`을 택했다.

### step 스펙이 도메인 정합성을 이길 수 없다 — 진입 차단 위치

step 문서는 진입 차단을 "USED/RESERVED 분기 **전**"에 두라고 명시했으나, developer가 그대로 적용하니 USED 예약의 같은 키 redirect 멱등 응답(ADR-026)과 형제 결제 테스트가 깨졌다. **정상 결제 완료 후 redirect 재도착(USED + 그 주문 SUCCEEDED)을 진입 전에 차단하면 정상 사용자에게 에러가 가기** 때문이다. developer는 차단을 USED 분기 **이후**(RESERVED 신규 승인에만 적용)로 교정했고, 이게 옳았다. step 스펙이 부정확했고 도메인 멱등 규칙이 우선이라는 사례다.

### 동시성에 영향 주는 step은 AC에 `concurrencyTest`를 포함해야 한다 (이번 작업 최대 교훈)

진입 차단(`PAYMENT_DUPLICATE`)이 같은 주문 동시 요청의 race 결과에 새 에러코드를 추가했는데, **step2의 Acceptance Criteria가 `test`+`integrationTest`만이라 execute.py가 회귀를 못 잡았다.** `concurrencyTest`를 AC로 가진 건 step1뿐이고, 그 시점엔 진입 차단이 아직 없었다. 회귀는 PR 리뷰 처리 중 전체 `concurrencyTest`를 돌려서야 드러났다:

- **CON-5(`SUCCEEDED 멱등`)**: setup이 `reservation.use()`를 빼고 payment만 SUCCEEDED로 만든 **비현실적 상태**(같은 reservation에서는 실제로 불가능 — `create()`가 use+payment를 원자적으로 처리). 진입 가드가 이를 `PAYMENT_DUPLICATE`로 막아 결정적 실패. → setup을 USED로 현실화해 멱등 경로를 복원했다.
- **CON-1·CON-2(winner 성공 시나리오)**: winner SUCCEEDED 후 RESERVED로 읽은 패자가 진입 가드에 걸리는 **현실적 flaky race**. → race 허용 목록에 `PAYMENT_DUPLICATE`를 추가했다.

교훈: **새 진입/상태 가드를 추가하는 step은 AC에 `concurrencyTest`를 반드시 포함**한다. 동시성 테스트가 분리 태스크라 기본 `test`에서 빠지므로, 영향 범위를 AC와 대조하지 않으면 회귀가 머지 단계까지 숨는다.

### "RESERVED + SUCCEEDED 공존"은 어디서 생기나

같은 reservation에서는 발생 불가능하다(payment 존재 ⇒ 그 reservation은 USED). 진입 가드(ADR-037)가 실제로 잡는 건 **다른 reservation 간** 상황이다 — 주문 O를 R1(USED)로 결제 성공한 뒤 R2(RESERVED, 다른 merchantPayKey)로 같은 주문을 재결제하는 경우. orderId를 공유하는 SUCCEEDED payment가 있어 `existsApprovedByOrderId`가 차단한다. 에러코드를 `PAYMENT_RESERVATION_ALREADY_USED`(예약 단위)가 아니라 `PAYMENT_DUPLICATE`(주문 단위, 최종 보루와 공유)로 둔 이유가 여기 있다.

### USED 재사용 에러코드 일관화 (PR 리뷰)

Gemini 리뷰가 "USED 예약에 다른 pgPaymentId로 온 요청은 `PAYMENT_NOT_FOUND`가 아니라 이미 소비된 예약 재사용"이라고 지적했다. 타당했다 — 같은 race(다른 pgPaymentId 재사용)가 concurrent(`saveUsed` 낙관적 락) 경로에서는 `PAYMENT_RESERVATION_ALREADY_USED`, sequential(USED 분기) 경로에서는 `PAYMENT_NOT_FOUND`로 갈리고 있었다. USED 분기의 `orElseThrow`를 `PAYMENT_RESERVATION_ALREADY_USED`로 바꿔 두 경로를 일관화했다(ADR-036에 반영).

### 예약 미발견 전용 코드 분리 (PR 리뷰 후속)

리뷰 마무리 단계에서 "예약(Reservation) 미발견이 결제(Payment) 미발견과 같은 `PAYMENT_NOT_FOUND`('결제를 찾을 수 없습니다')를 쓰는 게 어색하다"는 지적이 나왔다. 조회 단일화로 남의 키까지 `PAYMENT_NOT_FOUND`로 흡수하면서 그 사용이 더 두드러졌고, 이번에 도입한 예약 전용 `PAYMENT_RESERVATION_ALREADY_USED`와도 비대칭이었다. 예약 미발견 전용 코드 `PAYMENT_RESERVATION_NOT_FOUND`(404, "결제 예약을 찾을 수 없습니다")를 신설해 승인 진입 역조회 실패만 교체하고, PG/history 경로의 `PAYMENT_NOT_FOUND`는 그대로 뒀다(ADR-038에 반영). 도메인 엔티티(Reservation vs Payment)가 다르면 미발견 코드도 분리하는 게 의미상 옳다는 사례다.

---

## 4. 한 줄 요약

정합성은 #230이 보장하고, 이 작업은 그 앞단에서 PG 청구 도달 빈도를 낮추는 심층 방어다. 가장 값진 교훈은 **동시성 가드를 추가하는 step은 AC에 `concurrencyTest`를 넣어야 회귀가 숨지 않는다**는 것이었다.
