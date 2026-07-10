# MDC 정리는 최외곽 요청 필터의 clear()와 nested 스코프의 per-key remove 두 규칙으로 한다

- Status: accepted
- Date: 2026-07-10

## Context

요청 스레드의 MDC 키는 여러 필터·스코프가 겹겹이 쌓는다: 최외곽 요청 필터의 `traceId`, 인증 필터의 `memberId`, 유스케이스가 진입 시 push하는 도메인 키(`orderId`·`pgPaymentId`). 정리 책임의 기준이 없어 두 문제가 있었다. (1) 인증은 안쪽 필터에서 일어나는데 memberId를 실어야 할 요청 종료 접근 로그는 그 바깥 필터가 인증 필터의 정리 *이후* 찍어, memberId를 request attribute로 릴레이하고 요청당 두 번 put/remove했다. (2) 로깅 규약이 "자신이 push한 키만 remove"와 "요청 종료 시 반드시 `MDC.clear()`"를 함께 담아 상충처럼 읽혔고, 코드는 최외곽 필터에서도 per-key remove를 쓰며 `clear()`를 금지해 규약과 어긋났다.

## Decision

MDC 정리를 스코프 경계 기준 두 규칙으로 나눈다. (a) **최외곽 요청 필터**(요청 스레드의 가장 바깥 필터)는 `finally`에서 `MDC.clear()`로 그 스레드의 MDC를 통째 비운다. (b) 최외곽이 아닌 **nested 스코프**(유스케이스의 도메인 키, 비동기 경계(Kafka/Outbox)에서 복원한 traceId)는 자신이 push한 키만 remove하고, 운영 코드에서 `clear()`를 쓰지 않는다(테스트 격리용 `clear()`는 허용). memberId는 (a)에 얹는다 — 인증 필터는 populate만 하고, 접근 로그 필터는 memberId를 직접 관리하지 않는 순수 로거로 남으며, 최외곽 필터의 `clear()`가 정리한다. 비동기 경계의 per-key 정리(→ PR#157)는 (b) 규칙과 정합하며 그대로 유지된다.

## Consequences

memberId는 요청당 한 번만 put되고 필터 간 request attribute 릴레이가 사라져 접근 로그 필터가 인증 관심사와 분리된다. 로깅 규약의 두 문장이 적용 스코프가 다른 두 규칙으로 정합화된다. 감수하는 trade-off: (a) 규칙은 그 필터가 MDC를 만지는 최외곽으로 유지됨을 전제한다. 최외곽 요청 필터는 `HIGHEST_PRECEDENCE + 10`이라 그보다 바깥(`+0~+9`)에 MDC 키를 push하는 필터를 두면, 최외곽의 `clear()`가 그 키를 그 필터의 `finally` 전에 지운다. 그런 필터를 도입하려면 이 정리 모델을 먼저 재검토해야 한다.
