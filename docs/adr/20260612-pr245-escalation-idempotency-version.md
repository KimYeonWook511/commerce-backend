# escalation 멱등을 조건부 UPDATE에서 @Version + escalate() 도메인 메서드로 환원한다

- Status: accepted
- Date: 2026-06-12

## Context

이 결정은 escalation 종착·통지를 `escalatedAt` 직교 필드로 표현하기로 한 기존 결정(→ PR#242)의 escalation 멱등 메커니즘(조건부 UPDATE 영향 행 수) 부분만 갱신한다. `escalatedAt` 직교 필드·status 불변·통지 정신은 유지한다.

기존 결정(→ PR#242)은 `Payment`에 `@Version`이 없어 메모리 가드로 race를 못 막으므로 DB 레벨 원자성(조건부 UPDATE 영향 행 수)으로 멱등을 보장했다. `@Version` 도입(→ PR#245)으로 그 전제가 사라졌다.

규칙(어떤 상태에서·한 번만)을 SQL WHERE에서 도메인 메서드로 올리면 네 전이(`succeed`/`fail`/`markUnknown`/`escalate`)가 모두 엔티티 가드에 모여 일관되고 표현력이 좋다. 통지 정확히 1회는 `@Version`이 보장한다.

## Decision

`escalateIfPending`(repository 조건부 UPDATE)을 제거하고 `Payment.escalate(now)` 도메인 메서드로 환원한다. escalation 가능 상태(`UNKNOWN/REQUESTED`)·멱등(`escalatedAt IS NULL`) 가드를 엔티티 안에 둔다. 통지 주체는 escalate transition(`find → escalate() → saveChecked`) 성공으로 판정하고, 동시 시도 진 쪽은 `PAYMENT_CONCURRENTLY_MODIFIED`로 skip한다(통지 정확히 1회).

## Consequences

`@DynamicUpdate`가 없어 version bump 없는 CAS 유지 시 동시 `fail()` save가 `escalatedAt`을 stale로 덮을 위험이 있어, 결국 환원이 더 단순하고 안전하다. 통지 주체 판정이 영향 행 수에서 save 성공/예외 흡수로 바뀌어 `PaymentEscalationConcurrencyTest`를 save 성공 1회 검증으로 갱신했다. CANCEL escalation은 CANCEL 대사 미구현으로 범위 밖.

관련: 낙관 락 충돌 처리 계층을 가른 결정(→ PR#245), #243.
