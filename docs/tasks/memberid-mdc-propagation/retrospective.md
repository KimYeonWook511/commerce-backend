# memberid-mdc-propagation 회고

## 배경

이 작업은 이슈 #145로 진행한 memberId MDC 전파 태스크다. 로깅 Epic #133의 P3/P4 누락분을 보완한다.

P1(이슈 #128 logback) 작업에서 콘솔 패턴 `memberId=%X{memberId:-}`과 파일 JSON `<mdc/>` provider가 MDC를 읽도록 이미 준비됐다. P2(이슈 #129 traceId Filter) 작업에서는 `MDC.clear()` 대신 `MDC.remove("traceId")`를 채택하면서 "후속 memberId push를 위한 자리 비움"을 명시적으로 결정했다. 그러나 P3/P4 어느 작업에서도 실제 push가 구현되지 않아, 그 결정이 코드에 반영되지 않은 채 남아 있었다.

작업 전 저장소 상태는 다음과 같았다.

- prod 모든 로그에 `memberId=` 필드가 빈 문자열로 출력
- `JwtAuthenticationFilter`가 `AuthenticationContext.set(memberId, role)`만 호출하고 `MDC.put("memberId", ...)`를 호출하지 않음
- `AccessLogFilter` access log("요청 시작/종료")에도 memberId 빈 값 — `JwtAuthenticationFilter`보다 바깥 Filter라 인증 결과를 직접 읽지 못하는 구조
- `JwtAuthenticationFilter`가 `@Component` 자동 등록(LOWEST_PRECEDENCE 기본값)이라 미래 Filter 추가 시 순서 충돌 위험 내재

---

## 결정사항 요약

| 결정 | 채택 | 비채택 후보 |
|------|------|-------------|
| MDC push 위치 | A: `JwtAuthenticationFilter` 내부 | B: 별도 `MemberIdMdcFilter` 분리 |
| AccessLog memberId 포함 | i: request attribute 경유 포함 | 이슈 본문대로 제외 / Filter 순서 뒤집기(불가) |
| 상수 owner | `AccessLogFilter.MEMBER_ID_ATTRIBUTE` (consumer) | `JwtAuthenticationFilter` / 별도 상수 클래스 |
| attribute key 값 | `"AccessLogFilter.memberId"` (클래스명 prefix) | FQN / `Class.getName()+` / 단순 `"memberId"` |
| `JwtAuthenticationFilter` 등록 방식 | `FilterRegistrationBean` (order HIGHEST_PRECEDENCE + 30) | `@Component` 유지 / `@Order` 추가 |
| step/commit 분리 | refactor + feat + docs + docs (4 step, 4 commit) | 단일 step 통합 |

상세 근거는 `docs/tasks/memberid-mdc-propagation/adr.md`에 기록했다.

---

## 진행 중 트레이드오프

### 옵션 A(JwtAuthenticationFilter 내부) vs 옵션 B(별도 MemberIdMdcFilter)

B는 단일 책임 원칙과 `TraceIdFilter`와의 일관된 패턴이라는 구조적 장점이 있다. 그러나 새 Filter가 `AuthenticationContext`를 의존하고 `JwtAuthenticationFilter`보다 안쪽 order를 명시해야 하며, Config Bean도 함께 추가되어 변경 파일이 늘어난다. 운영 가치는 A와 동일하므로 4줄 추가로 같은 효과를 내는 A를 선택했다.

trade-off: `JwtAuthenticationFilter`가 SLF4J/MDC를 알게 되어 인증 Filter에 로깅 책임 일부가 혼입된다. 현재 규모에서는 감수할 만한 수준이며, 별도 분리가 필요해지면 후속 작업에서 재검토 가능하다.

### AccessLog access log에 memberId 포함

이슈 본문은 AccessLogFilter 처리를 명시하지 않았다. 그러나 access log가 "어떤 사용자가 어떤 요청을 했나"를 한 줄로 추적하는 핵심 데이터인 점에서 운영 가치가 가장 크다고 판단해 포함을 결정했다.

Filter 순서를 뒤집어 `JwtAuthenticationFilter`를 바깥에 두는 옵션은 인증 실패 시 `chain.doFilter`를 호출하지 않아 access log 누락 발생 — 채택 불가. `AccessLogFilter`가 JWT를 직접 재파싱하는 옵션은 인증 로직 중복 — 채택 불가.

`request.setAttribute`를 선택한 이유: `JwtAuthenticationFilter` finally에서 `AuthenticationContext.clear()`가 호출되므로 `AccessLogFilter` finally 시점에 `AuthenticationContext`를 직접 읽으면 null이다. request attribute는 한 요청 처리 중 서버 메모리에만 살아있어 ThreadLocal 초기화와 무관하므로 자연스럽게 전달 가능하다.

단, "요청 시작" access log는 `chain.doFilter` 전이라 memberId가 빈 값으로 유지된다 — 의도된 동작.

### 상수 owner: AccessLogFilter(consumer)

`JwtAuthenticationFilter`(producer)에 두면 `common.log → security` 의존이 발생해 방향이 어색하다. 별도 상수 클래스는 키 하나를 위한 YAGNI다. consumer-defined contract: "내 access log에 memberId가 필요하다"고 요구하는 쪽이 key를 정의하는 것이 의미상 자연스럽다.

attribute key 값을 `"AccessLogFilter.memberId"`(클래스명 prefix)로 한 이유: FQN은 패키지 이동 시 수동 갱신, `Class.getName()+`는 runtime 평가, 단순 `"memberId"`는 다른 라이브러리와 충돌 위험이 있다. 클래스명 prefix는 짧고 읽기 쉬우며 패키지 이동에 무관하고 충돌이 사실상 불가능하다. 클래스 rename 시에만 수동 갱신이 필요하다(한 줄).

### JwtAuthenticationFilter 등록 방식 변경

이슈 본문 범위 밖이었으나 같은 영역(Filter 등록 정책)을 두 번 만지지 않기 위해 이번에 포함했다. `@Component` 유지 시 다른 `@Component` Filter가 추가되면 같은 LOWEST_PRECEDENCE에서 Bean name 알파벳순 등 암묵적 규칙에 의존하게 된다. 동작 변화 없는 refactor라 회귀 위험이 낮고, `TraceIdFilterConfig`/`AccessLogFilterConfig`와 동일한 패턴으로 통일된다.

### step/commit 분리

동작 무변화 refactor(`register-jwt-auth-filter-explicitly`)와 동작 변화 feat(`propagate-memberid-mdc`)를 한 commit에 묶으면 reviewer가 회귀 없음과 동작 변화를 독립적으로 확인하기 어렵다. 목적이 다른 변경은 분리한다는 컨벤션(`commit-conventions`, `feedback_separate_commits`)에 따라 4 step, 4 commit으로 분리했다.

---

## 이슈 본문과의 차이

| 항목 | 이슈 본문 | 실제 구현 | 사유 |
|------|----------|-----------|------|
| AccessLogFilter access log 처리 | 명시 없음 | 포함 | 운영 추적 가치 최우선 |
| JwtAuthenticationFilter 등록 방식 | 명시 없음 | @Component → FilterRegistrationBean | 미래 Filter 충돌 회피, 같은 영역 재방문 비용 회피 |

이슈 본문은 변경하지 않았다. 추가 결정의 사유는 ADR과 이 회고록에 기록한다.

---

## 후속 작업 제안

- **`@Async`, Kafka consumer, `@TransactionalEventListener` MDC 전파**: 비동기 경계에서 MDC가 초기화되어 memberId/traceId가 유실된다. `@Async`는 `TaskDecorator`로, Kafka consumer는 헤더 propagation으로, `@TransactionalEventListener`는 이벤트 publish 시점 MDC 복사로 각각 다룬다. `docs/logging-conventions.md §8` 마지막 항목.
- **MSA 분리 시 Gateway header 패턴**: Auth 서비스가 분리되면 `X-User-Id` 헤더 주입 패턴으로 전환 검토. Filter에서 헤더를 읽어 MDC에 채우는 핵심 흐름은 동일하다.
- **OpenTelemetry baggage**: traceId/memberId를 분산 전파할 때 W3C Trace Context + baggage 표준 도입 검토. 현재 UUID 기반 traceId는 128-bit 식별자와 구조적으로 호환된다.
- **AccessLogFilter "요청 시작" access log에 memberId 채우기**: 이번에는 chain.doFilter 전 시점이라 의도적으로 제외. 필요해지면 인증 정보를 진입 시점에 미리 알 수 있는 별도 mechanism(예: pre-auth header 기반) 검토.
- **MdcKeys 상수 클래스 추출**: 현재 `"memberId"`, `"traceId"` 문자열이 Filter와 테스트에 직접 쓰인다. 키가 늘어나는 시점에 `MdcKeys` 상수 클래스를 추출해 키 불일치 위험을 제거한다.
