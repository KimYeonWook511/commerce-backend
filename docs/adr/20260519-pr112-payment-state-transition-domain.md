# PaymentAttempt succeed/fail 메서드는 상태 전이를 도메인에서 검증한다

- Status: accepted
- Date: 2026-05-19

## Context

- **배경**: 기존 mark 메서드 4개(`markApproveSucceeded`, `markApproveFailed`, `markCancelSucceeded`, `markCancelFailed`)는 (1) `status == REQUESTED`, (2) `type`이 메서드 의도와 일치를 동시에 검증했다. 분리된 두 Service(`PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`)가 항상 올바른 type의 attempt만 조회·전달하므로 도메인 내 type 가드는 방어 가치를 잃어 제거됐다. mark 4개는 `succeed`/`fail` 2개로 통합됐다. 본 ADR의 핵심 결정("REQUESTED 외 전이 거부 + failCode 보호")은 status 가드만으로 동일하게 보존된다.
- **이유**: 멱등성은 상위 레이어(`PaymentApprovalAttemptService.getOrCreate` + `NaverPayApprovalService.processApproveAttempt` switch)에서 처리되므로 `succeed`/`fail`은 멱등을 책임지지 않는다. Order 도메인의 명시적 선조건 검증 패턴과 일관. 도메인 무결성 위반은 내부 결함 신호라 외부 입력 mismatch를 409로 거부하는 기존 결정(→ PR#101)과 구분되도록 500.

## Decision

`PaymentAttempt`의 `succeed(respondedAt)` 및 `fail(failCode, detail, respondedAt)` 메서드는 호출 시점에 `status == REQUESTED` 조건을 검증한다. 위반 시 `PaymentException`(`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`, 500)으로 거부. 멱등 자기 전이도 거부.

## Consequences

- **트레이드오프**: 새 검증 도입 시 catch 블록 안에서 `succeed`/`fail`이 호출되는 호출처(예: `NaverPayApprovalService.failApproveAndCancelApprovedPayment`)는 race window에서 throw해도 보상 트랜잭션이 중단되지 않도록 적절히 보호해야 한다. 보상 catch 2차 예외 처리의 일반 원칙은 1차 예외 ERROR 로깅 + 의도 캡슐화 메서드 패턴을 따르는 별도 결정(→ PR#113)으로 정의했다(`docs/exception-strategy.md` 참조). 상세는 `docs/tasks/payment-attempt-state-transition-policy/adr.md` 참조.
- **후속** (`payment-compensation-policy` task): task 내부에서 임시 처방으로 두었던 try-catch 보호 한 곳이 보상 진행 여부를 Payment 엔티티 존재 여부로 판단하는 결정(→ PR#118)으로 대체됐다. race window에서 `succeed`/`fail`이 throw되는 경로 자체가 줄어들어 본 ADR의 엄격한 검증 원칙은 그대로 유지된다. #117(멱등 자기 전이 허용) close.
