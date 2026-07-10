# ADR (staging): mdc-scope-ownership

> 이 파일은 이번 spec에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 spec 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.

---

## ADR-L1: MDC 정리를 "최외곽 필터 clear() + nested per-key" 2-규칙 모델로 한다

- 상태: accepted
- supersedes: 없음 (기존 traceId 필터의 per-key 정리 선택을 조정하나, 별도 ADR로 존재하지 않던 코드 내 결정이라 supersede 대상 없음)
- superseded-by: 없음

### 배경

요청 스레드의 MDC 키는 여러 필터·스코프가 겹겹이 쌓는다: 최외곽 `TraceIdFilter`의 `traceId`, 인증 필터의 `memberId`, 그리고 유스케이스가 진입 시 push하는 도메인 키(`orderId`·`pgPaymentId`). 정리 책임을 어디에 둘지가 미정이었고, 그 미정이 두 가지 문제로 드러났다.

1. memberId가 요청당 두 번 put/remove되고 필터 간 request attribute 릴레이로 이어져 있었다. 인증은 안쪽 `JwtAuthenticationFilter`에서 일어나는데, memberId를 실어야 할 요청 종료 로그는 바깥 `AccessLogFilter`가 인증 필터의 정리 *이후* 찍기 때문이다.
2. `logging-conventions.md`가 §핵심 원칙("자신이 push한 키만 remove")과 §8("요청 종료 시 반드시 `MDC.clear()`")로 상충처럼 읽혔다. 코드(`TraceIdFilter`)는 `removeTraceId()`(per-key)를 쓰며 `clear()`를 금지하는 주석을 달아, 문서 §8과도 어긋나 있었다.

### 고려한 대안

- **(A) `AccessLogFilter`가 memberId를 remove** (이슈 원안): Jwt가 put만, `AccessLogFilter`가 종료 로그 직후 "마지막 소비자"로서 removeMemberId. per-key 원칙 유지. 기각 이유: `AccessLogFilter`(순수 접근 로거)가 인증 관심사인 memberId 정리 책임을 떠안아 결합이 생긴다. put과 remove가 서로 다른 필터로 갈라지는 혼란도 남는다.
- **(C) `TraceIdFilter`가 traceId·memberId를 명시적 per-key remove**: 최외곽 필터가 `clear()` 대신 두 키를 각각 remove. 기각 이유: 최외곽 필터가 자기 것이 아닌 memberId를 열거해야 하고, 필터 레벨 키가 늘 때마다 remove 목록을 갱신해야 한다. clear()로 얻는 "스코프 전체 정리"의 단순함을 잃는다.
- **(D) 필터 순서를 뒤집어 인증 필터를 최외곽으로**: 인증 필터가 put+remove를 자기 안에서 대칭 처리. 기각 이유: 인증 필터가 401에서 조기 반환하면 그 바깥이 된 `AccessLogFilter`를 건너뛰어 미인증 요청의 접근 로그가 사라진다.

### 결정 내용

정리 책임을 스코프 경계 기준 2-규칙으로 나눈다.

- **(a) 최외곽 요청 필터 = 스레드 스코프 정리.** 요청 스레드의 가장 바깥 필터(`TraceIdFilter`)가 `finally`에서 `MDC.clear()`로 그 스레드의 MDC를 통째 비운다.
- **(b) nested 스코프 = 자기 키만 remove.** 최외곽이 아닌 곳에서 push한 키(도메인 유스케이스의 `orderId` 등, 비동기 경계(Kafka/Outbox)에서 복원한 `traceId`)는 자신이 push한 키만 finally에서 remove한다. 운영 코드에서 nested `clear()`는 금지한다(테스트 격리용은 허용).

memberId는 (a)에 얹는다: `JwtAuthenticationFilter`가 populate만 하고, `AccessLogFilter`는 순수 로거로 남으며, 최외곽 `clear()`가 정리한다. 이에 따라 `TraceIdFilter`의 `removeTraceId()`는 `MDC.clear()`로 바뀌고, "`clear()` 금지" 주석은 "최외곽이라 `clear()`로 정리" 취지로 교체한다.

### 근거

- 최외곽 필터의 `finally`는 모든 안쪽 스코프가 풀린 요청 종료 지점이라, 그 시점의 `clear()`는 바깥에 남은 스코프가 없어 남의 키를 조기 삭제하지 않는다. 스레드 풀 반납 전 잔류를 막는 최종 보루가 된다.
- 반대로 nested 스코프에서 `clear()`를 부르면 바깥·형제 스코프의 살아있는 키를 날린다. 그래서 (b)의 per-key는 비동기 경계 결정(ADR #157 "자신이 push한 키만 정리")과 같은 이유로 유지된다. 두 규칙은 모순이 아니라 적용 스코프가 다르다.
- (a)를 택해 `AccessLogFilter`를 memberId 정리에서 떼어내면, 접근 로거는 "MDC를 읽어 찍기"만 하는 순수 역할로 남고 인증 관심사와 결합하지 않는다.

### 결과

- 요청당 memberId put이 1회로 줄고 request attribute 릴레이가 사라진다. 문서 §8과 §핵심 원칙이 적용 스코프가 다른 두 규칙으로 정합화된다.
- **감수할 trade-off**: 이 모델은 `TraceIdFilter`가 MDC를 만지는 최외곽 필터로 유지됨을 전제한다. `TraceIdFilter`는 `HIGHEST_PRECEDENCE + 10`이라 `+0~+9`에 더 바깥 필터를 둘 여백이 있는데, 그 자리에 MDC 키를 push하는 필터를 추가하면 최외곽 `clear()`가 그 키를 조기 삭제한다. 그런 필터를 도입하려면 이 정리 모델을 먼저 재검토해야 한다(문서·주석에 이 전제를 남긴다).
