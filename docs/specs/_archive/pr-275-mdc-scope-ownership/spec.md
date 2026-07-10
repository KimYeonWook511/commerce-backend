# Spec: mdc-scope-ownership

> 이 문서는 **이 spec만의 작업용 스펙**이다(제품 PRD가 아니다).
> 이 spec이 *무엇을·왜* 하는지를 확정하는 정본이고, 작업 종료 후 동결한다.
>
> spec 폴더는 작업 중 `.gitignore`로 휘발 상태다. 작업 종료 시(Stage 8, Root Sync) 이 spec과
> 설계 문서(architecture·adr·api-spec·db-schema)·step 문서(`step<N>.md`)는
> `docs/specs/_archive/pr-<PR번호>-<spec명>/`로 복사되어 **같은 PR에 커밋**된다 —
> 이것이 "왜 이 spec을 했나"의 영구 기록이다.
> 진행 상태 파일(`index.json`·`workflow-checklist.json`·`ac-output`·`logs`)은 휘발로 남고 승격하지 않는다.
> 이 spec은 루트 PRD로 승격하지 않는다.

- 상태: Draft
- 입력(이 spec의 출처): GitHub 이슈 #267 "refactor: MDC 정리를 스코프-오너십 모델로 전환"
- 변경 포인터: 없음. 단, 정리 모델은 이슈의 "AccessLogFilter가 memberId remove"에서 Clarify(2026-07-10)를 거쳐 **"최외곽 필터 `MDC.clear()` + nested per-key"** 모델로 정련됨. 관측 결과(요청당 put 1회·릴레이 없음·잔류 없음)는 동일.

---

## 배경 (왜) *(필수)*

현재 memberId MDC는 요청당 두 번 넣었다 빠지며, 그 사이를 request attribute 우회로가 잇는다.

- 필터 체인은 바깥→안쪽으로 `TraceIdFilter`(order +10) → `AccessLogFilter`(+20) → `JwtAuthenticationFilter`(+30)다.
- 인증은 안쪽 `JwtAuthenticationFilter`에서 일어나 memberId를 알게 되지만, 요청 종료 로그는 바깥 `AccessLogFilter`가 인증 필터의 `finally`(이미 memberId를 remove함) *이후*에 찍는다.
- 그래서 종료 로그에 memberId를 실으려고, 인증 필터가 `request.setAttribute(MEMBER_ID_ATTRIBUTE)`로 값을 릴레이하고, `AccessLogFilter`가 종료 로그 직전 그 값을 MDC에 **재삽입**했다가 직후 **재제거**한다. 결과적으로 요청당 put/remove가 각 2회이고, 필터 간 request attribute 결합이 생긴다.

또한 `docs/logging-conventions.md`가 표면적으로 모순돼 보인다. 핵심 원칙 요약(§핵심 원칙)은 "자신이 push한 키만 remove, `MDC.clear()` 주의"라고 하는데, §8 "정리"는 "요청 종료 시 **반드시 `MDC.clear()`를 호출**"이라고 한다. 이 둘은 **적용 스코프가 다른 두 규칙**인데 한 문서 안에서 상충처럼 읽힌다.

이 spec은 MDC 정리를 **적용 스코프가 분명한 2-규칙 모델**로 정립해 우회로를 없애고 문서를 정합화한다.

## MDC 정리 모델 (이 spec의 핵심 결정)

정리 책임을 두 규칙으로 나눈다. 판별 기준은 "내가 스레드의 요청 스코프 경계인가?"이다.

- **(a) 최외곽 요청 필터 = 스레드 스코프 정리.** 요청 스레드의 가장 바깥 필터(`TraceIdFilter`)가 `finally`에서 `MDC.clear()`로 그 스레드의 MDC를 통째 비운다. 이 지점은 요청이 완전히 끝나고 모든 안쪽 스코프가 풀린 뒤라, 스레드 풀 반납 전 잔류를 막는 **최종 보루**다. 바깥에 남은 스코프가 없으므로 남의 키를 조기 삭제할 위험이 없다.
- **(b) nested 스코프 = 자기 키만 remove.** 최외곽이 아닌 곳에서 push한 로그 MDC 키(도메인 유스케이스의 `orderId`·`pgPaymentId`, 비동기 경계(Kafka/Outbox)에서 복원한 `traceId` 등)는 **자신이 push한 키만** finally에서 remove한다. 자신이 스레드 경계가 아니라 남의 살아있는 스코프에 얹혀 돌기 때문이다(여기서 `clear()`를 부르면 바깥/형제 스코프 키를 날린다).

memberId는 (a)에 얹힌다: 안쪽 `JwtAuthenticationFilter`가 populate만 하고, 최외곽 `TraceIdFilter`의 `clear()`가 요청 끝에서 정리한다. `AccessLogFilter`는 memberId를 직접 관리하지 않는 순수 로거로 남는다.

## 사용자 시나리오 & 테스트 *(필수)*

> 여기서 "사용자"는 운영/로그 관측자다. 관측 가능한 결과는 "접근 로그에 memberId가 정확히 실리고, 요청 종료 후 스레드에 MDC 잔류가 없다"이다.

### 시나리오 1 — 인증된 요청의 접근 로그에 memberId가 실린다 (우선순위: P1)

인증 토큰이 있는 요청이 정상 처리되면, `AccessLogFilter`의 요청 종료 로그에 그 요청의 memberId가 실려야 한다.

**인수 시나리오(Given/When/Then)**:

1. **Given** 유효한 access token을 가진 요청, **When** 요청이 정상(200) 처리되면, **Then** 요청 종료 로그의 MDC에 그 memberId가 실린다.
2. **Given** 인증된 요청 처리 중, **When** memberId를 MDC에 넣는 지점을 관찰하면, **Then** put은 인증 필터에서 정확히 1회이고, request attribute 릴레이·재삽입은 0회다.

### 시나리오 2 — 요청 종료 후 MDC 잔류가 없다 (우선순위: P1)

스레드 풀이 재사용되어도 이전 요청의 MDC 키가 다음 요청에 새지 않아야 한다.

**인수 시나리오(Given/When/Then)**:

1. **Given** 인증된 요청이 종료된 직후, **When** 같은 스레드의 MDC를 확인하면, **Then** memberId·traceId 키가 남아 있지 않다(최외곽 `clear()`).
2. **Given** 미인증(401) 요청 또는 WHITELIST 경로 요청, **When** 요청이 종료되면, **Then** memberId는 애초에 실리지 않고 종료 후에도 MDC 잔류가 없다.
3. **Given** 필터 체인 처리 중 예외가 발생한 인증 요청, **When** 요청이 종료되면, **Then** 최외곽 `TraceIdFilter`의 `finally`가 항상 실행되어 MDC를 비운다.

### Edge Cases

- **미인증(401)·WHITELIST 경로**: 인증 필터가 memberId를 put하지 않고 조기 반환한다. 종료 로그엔 memberId가 없고, 최외곽 `clear()`가 traceId를 정리한다.
- **체인 예외**: 인증 필터가 memberId를 put한 뒤 체인에서 예외가 나도, 바깥 `AccessLogFilter`의 `finally`가 종료 로그를 찍고(memberId 실림), 그 바깥 `TraceIdFilter`의 `finally`가 `clear()`한다.
- **스레드 풀 재사용**: 직전 요청의 키가 잔류한 스레드에 새 요청이 와도, 각 필터의 put이 새 값으로 덮어쓰고 요청 끝의 `clear()`가 비워 누적되지 않는다.
- **`TraceIdFilter`보다 더 바깥 필터**: `TraceIdFilter`는 `HIGHEST_PRECEDENCE + 10`이라 `+0~+9`에 더 바깥 필터를 둘 여백이 있다. 그런 필터가 MDC 키를 push하면 `clear()`가 그 키를 조기 삭제한다 → 이 모델은 **`TraceIdFilter`가 MDC를 만지는 최외곽 필터로 유지됨**을 전제한다(제약사항 참조).

## 핵심 엔티티 / 데이터 모델 *(데이터를 다루면 필수)*

해당 없음 (DB·도메인 엔티티 변경 없음). 다루는 것은 요청 스레드의 MDC 컨텍스트라는 런타임 상태뿐이다.

- **MDC 키의 수명(lifecycle)**:
  - `traceId`: `put`(TraceIdFilter, 최외곽) → 요청 내내 유지 → 요청 끝 `clear()`(TraceIdFilter).
  - `memberId`: `put`(JwtAuthenticationFilter, 인증 성공 시) → 요청 처리 중 유지 → `AccessLogFilter` 종료 로그에서 소비 → 요청 끝 `clear()`(TraceIdFilter)로 정리. 별도 remove 지점 없음.

## 요구사항 *(필수)*

### 기능 요구사항

- **FR-001**: `JwtAuthenticationFilter`는 인증 성공 시 memberId를 MDC에 put만 한다. 자신의 `finally`에서 memberId를 remove하지 않는다. (`AuthenticationContext.clear()`는 이 spec 범위 밖으로 그대로 유지한다.)
- **FR-002**: memberId의 `request.setAttribute` 릴레이를 제거한다. `AccessLogFilter.MEMBER_ID_ATTRIBUTE` 상수와 그 사용처(Jwt의 setAttribute, AccessLogFilter의 attribute 소비)를 모두 제거한다.
- **FR-003**: `AccessLogFilter`는 memberId MDC를 직접 관리하지 않는다(순수 로거). 종료 로그는 인증 필터가 넣어둔 memberId가 MDC에 남아 있는 상태로 찍힌다. 종료 로그 직전 재삽입·직후 재제거 로직을 제거한다.
- **FR-004**: 최외곽 `TraceIdFilter`는 `finally`에서 `removeTraceId()` 대신 `MDC.clear()`로 요청 스레드의 MDC를 통째 정리한다. 단 `TraceIdFilter`가 `MDC`를 직접 import하지 않도록 `LogContext`에 `clear()`(내부에서 `MDC.clear()` 호출)를 추가하고 `TraceIdFilter`는 `LogContext.clear()`를 호출한다(§8 "MDC 키는 common.log에서 단일 관리"). 기존 "`MDC.clear()` 금지" 주석은 "최외곽 스레드 경계라 `clear()`로 스코프 전체를 정리한다"는 취지로 교체한다.
- **FR-005**: `docs/logging-conventions.md`의 MDC 정리 규칙을 위 2-규칙 모델로 정립한다. §8을 "(a) 최외곽 요청 필터가 요청 끝에서 `MDC.clear()`로 스레드 스코프 정리, (b) nested 스코프는 자신이 push한 키만 remove"로 재서술하고, §핵심 원칙 요약과 상충 없이 정합화한다. 운영 코드에서 nested 스코프가 `clear()`를 쓰는 것은 금지, 테스트 격리용 `clear()`는 허용을 명시한다.
- **FR-006**: memberId·traceId MDC 동작을 검증하는 테스트를 모두 새 모델에 맞게 갱신한다 — `JwtAuthenticationFilterTest`, `AccessLogFilterTest`, `AccessLogFilterIntegrationTest`, `TraceIdFilterTest`, `TraceIdFilterIntegrationTest`, `SecurityWebMvcTest`. request attribute 릴레이·AccessLogFilter의 memberId 관리를 검증하던 테스트는 새 모델(Jwt populate·최외곽 clear·AccessLog 순수 로거) 검증으로 대체한다.
- **FR-007**: `LogContext.removeMemberId()`는 변경 후 main 사용처가 0이 되므로 삭제한다. `removeTraceId()`는 비동기 경계(Kafka `TraceIdRecordInterceptor`, Outbox relay)가 (b) 규칙으로 계속 사용하므로 유지한다. `getMemberId()`는 테스트가 사용하므로 유지한다.

## 완료 기준 *(필수)*

- **SC-001**: 인증된 요청 1건에서 memberId MDC put이 정확히 1회(인증 필터)이고, request attribute 릴레이·재삽입이 0회다.
- **SC-002**: 인증된 요청의 접근 로그 종료줄 MDC에 memberId가 실린다.
- **SC-003**: 인증·미인증(401)·WHITELIST 어느 경우든 요청 종료 후 그 스레드의 MDC에 memberId·traceId 잔류가 없다.
- **SC-004**: `docs/logging-conventions.md`가 MDC 정리를 (a)최외곽 `clear()` (b)nested per-key 두 규칙으로 서술하고, "자신이 push한 키만 remove"와 "요청 끝 `clear()`"가 모순 없이 공존한다. 운영 코드의 nested `clear()` 금지·테스트 `clear()` 허용이 명시된다.
- **SC-005**: `./gradlew test`가 전부 통과한다.

## 제약사항 *(필수)*

- **필터 순서 불변**: `TraceIdFilter`(+10) → `AccessLogFilter`(+20) → `JwtAuthenticationFilter`(+30) order를 바꾸지 않는다. 이 포갬 구조가 "안쪽이 populate, 종료 로그가 소비, 최외곽이 정리"의 전제다. 순서를 뒤집으면 401 요청의 접근 로그가 사라지거나 memberId 소비 시점이 어긋난다.
- **`TraceIdFilter` 최외곽 유지 전제**: (a) 규칙의 `clear()` 안전성은 `TraceIdFilter`가 MDC를 만지는 가장 바깥 필터라는 데 의존한다. `HIGHEST_PRECEDENCE + 0~9`에 MDC 키를 push하는 필터를 추가하려면 이 정리 모델을 먼저 재검토해야 한다. 이 전제를 ADR·문서에 남긴다.
- **위험영역(인증·상태 전이)**: 이 작업은 인증 필터 동작과 MDC 컨텍스트 수명을 건드린다. 정리 모델은 Clarify에서 확정한 대로 고정하며 임의 변형하지 않는다.
- **동기화 원칙**: `docs/logging-conventions.md`는 코드를 *이끄는* 규칙 문서이므로, 규칙 변경은 ADR로 남기고 그 후속으로 문서를 갱신한다(루트 승격은 Stage 8). 비동기 경계 traceId 전파의 기존 결정("자신이 push한 키만 정리", ADR #157)은 (b) 규칙과 정합하며 바뀌지 않는다.

## 가정 (Assumptions)

- **`AuthenticationContext.clear()`는 유지**한다. 인증 컨텍스트(스레드 로컬)는 MDC와 별개이고 이 spec 범위 밖이다.
- **동기 요청 처리 전제**. 현재 코드에 Servlet async(DeferredResult·Callable·`@Async`·SSE 등) 사용처가 0건이라, 최외곽 `TraceIdFilter.finally`의 `clear()`가 단일 동기 dispatch 종료 지점에서 안전하다. async를 도입하면 dispatch 경계에서 MDC 전파·정리를 재검토해야 한다(이 spec 범위 밖).
- **ADR을 1건 추가**한다. MDC 정리 2-규칙 모델(최외곽 필터 `clear()` + nested per-key)은 코드를 이끄는 정책 결정이자 기존 `TraceIdFilter`의 per-key 선택을 조정하는 결정이므로 `adr.md`에 staging하고 Stage 8에서 루트로 승격한다.

## Clarifications

### 2026-07-10
- Q: memberId(및 traceId) MDC 정리를 어느 필터가, 어떤 방식으로 하나? → A: **최외곽 요청 필터(`TraceIdFilter`)가 `finally`에서 `MDC.clear()`로 스레드 스코프를 통째 정리**하고, 그 안쪽 nested 스코프(도메인 키·비동기 경계 복원분)는 자신이 push한 키만 remove하는 2-규칙 모델. memberId는 Jwt가 populate만, `AccessLogFilter`는 순수 로거, 정리는 최외곽 `clear()`가 담당. (근거: 최외곽 필터의 finally는 모든 안쪽 스코프가 풀린 요청 종료 지점이라 `clear()`가 남의 키를 조기 삭제하지 않고, 스레드 풀 반납 전 최종 보루가 된다. 이슈 원안의 "AccessLogFilter가 memberId remove"보다 AccessLogFilter를 순수 로거로 유지해 결합이 낮다. 단 `TraceIdFilter`가 MDC 최외곽 필터로 유지됨을 전제하며 이를 문서화한다.)
- 정리 위치를 이슈 원안(AccessLogFilter remove)에서 위 모델로 변경하면서 `TraceIdFilter`의 정리 로직(`removeTraceId()` → `MDC.clear()`)과 "`clear()` 금지" 주석도 이 spec 범위에 포함된다.

### 2026-07-10 (2차 스캔)
- Q: `TraceIdFilter`가 `MDC.clear()`를 직접 부르나, `LogContext`에 `clear()`를 추가하나? → A: `LogContext.clear()`를 추가하고 `TraceIdFilter`는 그것을 호출. `LogContext`가 MDC를 만지는 유일한 main 클래스이고 §8이 "MDC 키는 common.log에서 단일 관리"라고 하므로. (FR-004)
- Q: 사용처가 사라지는 `LogContext.removeMemberId()`는? → A: 삭제(사용처 없는 코드 미보존). `removeTraceId()`는 비동기 경계가 계속 쓰므로 유지, `getMemberId()`는 테스트가 쓰므로 유지. (FR-007)
- Q: 영향 테스트 범위? → A: 이슈의 3개에 더해 `TraceIdFilterTest`·`TraceIdFilterIntegrationTest`·`SecurityWebMvcTest`까지 memberId/traceId MDC를 검증하는 테스트 전부. 기존 `TraceIdFilterTest` 단언은 `clear()`로 바뀌어도 통과하며(잔류 없음을 검증하므로), "clear() 금지"를 못 박은 테스트는 없다. (FR-006)
- 확정: async 요청 미사용(코드 0건)으로 동기 전제. 최외곽 `clear()`가 안전.
