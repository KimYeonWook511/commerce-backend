# Payment에 @Version 낙관 락을 도입해 같은 행 동시 전이 lost update를 막는다

- Status: accepted
- Date: 2026-06-12

## Context

핵심 엔티티(Order/PaymentReservation/CartItem/Stock)는 모두 `@Version`을 갖는데 `Payment`만 없어, 같은 행 동시 read-modify-write 전이에서 lost update가 가능했다.

짧은 tx·낮은 충돌·부수효과 분리 워크로드에서 비관 락(행 FOR UPDATE)보다 낙관 락이 적합하고 다른 엔티티와의 일관성도 보존한다.

## Decision

`Payment`에 `@Version Long version`을 추가하고 V9 migration으로 `tbl_payment.version BIGINT NOT NULL DEFAULT 0`을 추가한다. 같은 행 동시 전이(succeed vs fail 등)의 lost update를 `@Version` 불일치(`OptimisticLockException`)로 감지한다. 자동 재시도 루프는 두지 않고 충돌은 흡수(종착) 또는 전파(succeed)로만 처리한다(낙관 락 충돌 처리 계층을 가른 결정 → PR#245).

## Consequences

기존 방어 — 생성 시 Reservation 동시 이중 use 가드를 `@Version` 낙관 락으로 구현한 기존 결정(→ PR#235), 이중 SUCCEEDED를 막는 `uk_payment_approved_order_key`, 승인 직렬화의 order 비관 락 — 와 직교하며 그 위에 같은 행 동시 전이 방어를 더한다. order `findByIdForUpdate` 비관 락은 payment+order 원자성·승인 반영 직렬화 목적이라 유지한다(낙관 전환은 부분취소 등 합산 검증 도입 시 재판단).

이 `@Version` 도입을 전제로 escalation 멱등은 `escalate()` 도메인 메서드로 환원됐다(→ PR#245). 관련 이슈: #243.
