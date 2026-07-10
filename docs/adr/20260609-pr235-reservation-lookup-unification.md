# reservation 조회를 `(memberId, merchantPayKey)` 로 단일화하고 예약 미발견을 `PAYMENT_RESERVATION_NOT_FOUND` 로 응답한다

- Status: accepted
- Date: 2026-06-09

## Context

#231. 조회가 merchantPayKey 단독이라 member 검증이 별도 분기로 남았고, 남의 키 존재 시 403 이 반환되어 키 존재 여부가 노출됐다. 또한 예약 미발견이 결제(Payment) 미발견과 같은 `PAYMENT_NOT_FOUND`("결제를 찾을 수 없습니다")로 응답되어, 결제창 준비물(Reservation)과 PG 사건(Payment)의 미발견 의미가 섞였다.

- **고려한 대안**: (A) 현행 유지(키 조회 후 memberId 분기) — 403 이 키 존재를 노출하고 분기가 흩어진다. (B) 예약 미발견도 공용 `PAYMENT_NOT_FOUND` 유지 — 404 하나로 단순하나 Reservation·Payment 미발견 의미가 섞이고, 이번에 도입한 예약 전용 `PAYMENT_RESERVATION_ALREADY_USED` 와 비대칭이다.
- **이유**: 조회 조건에 member 검증을 흡수해 분기를 정리하고, 남의/없는 키를 예약 미발견으로 흡수해 키 존재를 비노출(보안)한다. 응답 의미 변화(403→404)는 의도된 것이다. 예약 미발견 전용 코드는 Payment 미발견(`PAYMENT_NOT_FOUND`, PG/history 경로)과 의미를 분리하고 `PAYMENT_RESERVATION_ALREADY_USED` 와 대칭을 이룬다. PG/history 경로의 `PAYMENT_NOT_FOUND` 는 그대로 둔다.

## Decision

승인 진입의 Reservation 역조회를 `findByMemberIdAndMerchantPayKey(memberId, merchantPayKey)` 로 단일화한다. 기존 "merchantPayKey 조회 후 memberId 불일치 시 `PAYMENT_MEMBER_MISMATCH`(403)" 분기를 제거하고, 남의/없는 키는 모두 예약 미발견으로 보아 전용 코드 `PAYMENT_RESERVATION_NOT_FOUND`(404) 로 응답한다. `PAYMENT_MEMBER_MISMATCH` 에러코드를 제거한다.

## Consequences

남의/없는 키 승인 요청이 `PAYMENT_RESERVATION_NOT_FOUND`(404, "결제 예약을 찾을 수 없습니다") 가 된다. `PAYMENT_MEMBER_MISMATCH`(403) 가 응답 표면에서 사라진다.

관련: 결제 도메인 재설계 결정(→ PR#205), reservation 동시 이중 use 낙관적 락 결정(→ PR#235), #231.
