# escalation 종착·통지를 새 상태 대신 escalatedAt 직교 필드로 표현한다

- Status: accepted
- Date: 2026-06-11

## Context

escalation을 대사 스캔 시간 윈도우 상한으로 자동 제외만 하기로 한 기존 결정(→ PR#237)은 운영 가시성(통지·종착)을 #238로 미뤘다(보상된 APPROVE 결제 상태를 FAILED로 유지하고 새 상태 도입을 미룬 기존 결정(→ PR#236)의 재검토 trigger). 그 결과 6시간 초과 건이 통지 없이 `UNKNOWN`으로 묻혀 운영자가 능동 조회해야만 인지 가능했다.

status는 "결제에 일어난 사실"만 담고(→ PR#236), "운영자에게 위임됐나"는 직교 축이라 별 컬럼으로 분리한다. 새 status(ESCALATED)는 대사 종착에 새 결제 상태(MANUAL_REVIEW)를 도입하지 않기로 한 기존 결정(→ PR#237)이 같은 이유로 철회한 방향이고 "결론 났나/처리됐나"를 한 값에 뭉갠다. 별도 테이블은 한 번 통지·종착뿐이라 과하다(YAGNI). 멱등을 조건부 UPDATE 영향 행 수로 보장하는 것은 `uk_payment_approved_order_key` unique가 이중 SUCCEEDED를 막는 것과 같은 DB 레벨 멱등 방식이다.

## Decision

6시간 초과 미확정 APPROVE 결제(UNKNOWN/REQUESTED)를 운영자에게 통지하고 종착 표시할 때, 새 status를 만들지 않고 `Payment.escalatedAt`(nullable timestamp) 직교 필드에 escalation 시각을 기록한다. status는 그대로 유지한다. 중복 통지는 조건부 UPDATE(`SET escalated_at=:now WHERE escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED)`)의 영향 행 수로 막아, 영향 행 1인 호출만 통지한다. 대사 중 주문 없음(order==null) 정합성 오류도 FAILED 종착 후 운영자에게 통지한다.

## Consequences

status enum이 4개로 유지돼 단순하다. `escalatedAt` 기록(커밋) 후 통지가 best-effort라 전송 유실 시 재통지되지 않는다(진실 원천은 `escalatedAt` — 대사·보상 통지를 NotificationPort 추상화로 두기로 한 기존 결정(→ PR#237)의 정신). escalation 이력·단계가 필요해지면 별도 테이블로 승격할 여지를 남긴다. CANCEL escalation은 CANCEL 대사 미구현으로 범위 밖(별도 이슈).

이 결정의 escalation 멱등 메커니즘(조건부 UPDATE 영향 행 수)은 이후 @Version 기반 `Payment.escalate()` 도메인 메서드로 환원됐다(→ PR#245). `escalatedAt` 직교 필드·status 불변·통지 commit 후 best-effort 1회 정신은 유지된다.
