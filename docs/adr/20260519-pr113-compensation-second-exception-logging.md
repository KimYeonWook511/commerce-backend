# 보상 catch 2차 예외 처리는 1차 예외 ERROR 로깅 + 의도 캡슐화 메서드 패턴을 따른다

- Status: accepted
- Date: 2026-05-19

## Context

`PaymentAttempt` mark 메서드에 선조건 검증이 추가되며(→ PR#112) 보상 흐름이 catch 안에서 mark 호출 시 race window에서 `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`를 만날 수 있게 됐다. 임시로 `failApproveAndCancelApprovedPayment` 안에 `try { failApprove(...) } catch (PaymentException markEx) { log.warn(...) }`를 넣었지만 catch 범위가 너무 넓어 `PAYMENT_ATTEMPT_NOT_FOUND` 같은 의도치 않은 예외까지 삼키는 문제가 있었다. 동시에 `completeVerifiedApproval`의 상위 catch 두 곳(`PaymentException`, `CustomException`)에 1차 예외 `log.error`가 누락돼 운영 원인 추적이 어려웠다.

catch 안에서 호출하는 메서드를 "예외 안 던지는 의도 캡슐화 메서드"로 만들면 호출처에서 try-catch가 사라지고 도메인 상태(예: `status == REQUESTED`) 검사가 application 레이어로 누출되지 않는다. 호출처는 의도(예: "가능하면 실패 처리, 아니면 skip")만 표현하고 도메인 규칙은 서비스 경계 안에 머문다. 1차 예외 ERROR 로깅은 운영 모니터링에서 근본 원인을 항상 보존하는 최소 보장이다. 로그 레벨은 1차 = ERROR, 2차 = WARN으로 구분해 1차 원인을 더 강하게 노출한다.

## Decision

보상 흐름의 catch 블록은 (a) 진입 즉시 1차 예외를 `log.error`로 ERROR 레벨에 남기고, (b) 2차 시도가 던질 가능성이 있는 예외는 가급적 메서드 자체(`...IfRequested` 등)에서 캡슐화해 호출처에서 try-catch 없이 호출하도록 설계하고, (c) 그래도 던지는 경우 중요도에 따라 `log.warn` + 1차 예외 전파(덜 중요) 또는 Composite Exception(`addSuppressed`)으로 둘 다 전파(치명적) 한다. 의사결정 트리와 적용 예는 `docs/exception-strategy.md` "보상 catch 2차 예외 처리" 섹션 참조.

## Consequences

"Skip" 의도 캡슐화 메서드(`...IfRequested`)가 늘면 서비스 API 표면이 약간 넓어진다. 다만 호출처마다 try-catch 또는 if-status 검사가 흩어지는 것보다 응집도가 높다. Composite Exception(`addSuppressed`)은 치명적 케이스에서만 사용하고, 일반적으로는 catch 안 메서드를 "예외 안 던지게 설계"하는 쪽을 우선한다.
