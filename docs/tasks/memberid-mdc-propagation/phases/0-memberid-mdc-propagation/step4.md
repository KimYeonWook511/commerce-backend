# Step 4: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 전체 맥락과 결정을 파악하라:

- `docs/tasks/memberid-mdc-propagation/prd.md`
- `docs/tasks/memberid-mdc-propagation/architecture.md`
- `docs/tasks/memberid-mdc-propagation/adr.md`
- `docs/tasks/traceid-mdc-filter/retrospective.md` — 동일 영역 회고 참고 (포맷, 분량)
- Step 1~3 산출물 (수정된 코드와 docs/architecture.md)

## 작업

### `docs/tasks/memberid-mdc-propagation/retrospective.md` 신규 작성

`docs/tasks/traceid-mdc-filter/retrospective.md`의 분량/포맷을 참고하되, 이번 작업의 결정과 trade-off를 그대로 기록한다.

#### 포함할 절

1. **배경**
   - 이슈 #145 (memberId MDC 연결), Epic #133 P3/P4 누락분 보완
   - P2(#129 traceId Filter) 작업 시 `MDC.remove("traceId")`로 "후속 memberId push를 위한 자리 비움" 결정 → 실제 push가 빠진 채 남아 있던 상태
   - 작업 전 상태: prod 모든 로그에 `memberId=` 빈 값

2. **결정사항 요약 표**

   | 결정 | 채택 | 비채택 후보 |
   |---|---|---|
   | MDC push 위치 | A: `JwtAuthenticationFilter` 내부 | B: 별도 `MemberIdMdcFilter` |
   | AccessLog access log memberId | 포함 (i: request attribute) | 본문대로 제외 / Filter 순서 뒤집기 (불가) |
   | 상수 owner | `AccessLogFilter.MEMBER_ID_ATTRIBUTE` (consumer) | `JwtAuthenticationFilter` / 별도 상수 클래스 |
   | attribute key | `"AccessLogFilter.memberId"` (클래스명 prefix) | FQN / `Class.getName()+` / 단순 키 |
   | `JwtAuthenticationFilter` 등록 | `FilterRegistrationBean` (order +30) | `@Component` 유지 / `@Order` 추가 |
   | step/commit 분리 | refactor + feat + docs + docs (4 step) | 단일 step 통합 |

3. **진행 중 트레이드오프**
   - 옵션 A vs B: A는 변경 최소화, B는 단일 책임. A의 책임 혼입(인증 Filter가 MDC를 알게 됨) trade-off 인지. 운영 가치가 동일하므로 비용 우선.
   - AccessLog 처리 포함 결정: 이슈 본문이 명시하지 않았으나 access log의 memberId가 가장 운영 가치 큼. 단, "요청 시작" access log는 chain.doFilter 전이라 빈 값 유지 (의도된 동작).
   - request attribute 의존: `AuthenticationContext.clear()`가 `JwtAuthenticationFilter` finally에서 호출되므로 `AccessLogFilter` finally에서 `AuthenticationContext`를 직접 못 읽음 → request attribute 필요. ThreadLocal 신규 클래스보다 단순.
   - `JwtAuthenticationFilter` 등록 방식 변경: 이슈 본문 범위 밖이었으나, 같은 영역을 두 번 만지지 않기 위해 이번에 포함. 미래 Filter 추가 시 LOWEST_PRECEDENCE 충돌 회피.
   - step/commit 분리: 동작 무변화 refactor와 동작 변화 feat를 한 commit에 묶지 않는다 (commit-conventions). reviewer가 각 변화를 독립 검증 가능.

4. **이슈 본문과의 차이**
   - 추가 1: AccessLogFilter access log 처리 포함 (본문 "범위 밖")
   - 추가 2: `JwtAuthenticationFilter` 등록 방식 변경 (본문 미언급)
   - 두 추가 모두 사유를 회고록과 PR description에 기록 (이슈 본문은 변경하지 않음)

5. **후속 작업 제안**
   - **MSA 분리 시 Gateway header 패턴**: Auth가 분리되면 `X-User-Id` 헤더 주입 패턴으로 전환 검토. 핵심 흐름("들어온 메타데이터를 MDC에 채워라")은 동일.
   - **OpenTelemetry baggage**: traceId/memberId를 분산 전파할 때 W3C Trace Context + baggage 표준 도입 검토.
   - **`@Async`, Kafka consumer, `@TransactionalEventListener` MDC 전파**: 컨벤션 §8 마지막 항목. `TaskDecorator`, Kafka header propagation 등.
   - **`AccessLogFilter` "요청 시작" access log에 memberId 채우기**: 이번에는 의도적으로 제외. 필요해지면 인증 정보를 Filter chain 진입 시점에 미리 알 수 있는 별도 mechanism(예: pre-auth header 기반) 검토.

## 수정 가능 경로

- `docs/tasks/memberid-mdc-propagation/retrospective.md` (신규)
- `docs/tasks/memberid-mdc-propagation/**` (task 문서, 필요 시)

## Acceptance Criteria

```bash
./gradlew test
```

문서 변경이지만 테스트 회귀 없음 확인 차원.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다 (회귀 없음).
2. `retrospective.md` 내용이 Step 1~3 실제 산출물과 정합한지 확인.
3. ADR 결정 1~6이 모두 회고록의 표/본문에 반영되었는지 확인.
4. 분량은 `docs/tasks/traceid-mdc-filter/retrospective.md` 수준 (과하게 길지 않게).
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 회고 문서는 작성 후 사후 소급 수정하지 마라. 이유: 회고는 작업 종료 시점의 기록이며 역사 자료다 (`feedback_retrospective_immutable`).
- 코드 변경 금지. 이유: 이 step은 회고록 작성만 다룬다.
- task 문서(prd/architecture/adr) 추가 변경 금지. 이유: 이 시점에 task 문서는 확정 상태여야 한다.
- 다른 task의 회고록을 수정하지 마라.
