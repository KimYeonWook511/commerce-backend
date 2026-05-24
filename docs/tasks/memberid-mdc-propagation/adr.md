# 태스크 ADR

## 결정 1 — MDC push/remove를 `JwtAuthenticationFilter` 내부에 둔다 (옵션 A)

### 배경

별도 `MemberIdMdcFilter`로 분리하는 옵션(B)도 검토했다. B는 단일 책임/`TraceIdFilter`와 일관된 패턴이라는 구조적 장점이 있지만, 새 Filter가 `AuthenticationContext`를 의존하고 Filter 순서를 명시적으로 보장해야 한다(JwtAuth보다 안쪽). 이는 `JwtAuthenticationFilter`의 등록 방식 변경과 신규 Filter 2개(Filter + Config) 추가 비용을 수반한다.

### 결정 내용

`JwtAuthenticationFilter` 내부에서 인증 성공 직후 `MDC.put("memberId", ...)`, finally에서 `MDC.remove("memberId")`. 별도 Filter는 분리하지 않는다 (이슈 본문 결정 유지).

### 근거

- 4줄 추가로 같은 효과 (변경 최소화).
- `AuthenticationContext.set` / `clear`와 MDC push/remove가 한 메서드의 try-finally 안에서 짝이 맞아 라이프사이클 추적이 명확하다.
- 옵션 B의 운영 가치는 옵션 A와 동일하나 변경 파일 수, Filter 순서 관리 비용이 추가된다.

### 결과

- `JwtAuthenticationFilter`가 SLF4J/MDC를 알게 된다 (인증 책임에 로깅 책임 일부 혼입). 회고록에 trade-off 명시.
- 별도 Filter 분리가 필요해지면 후속 작업에서 재검토 가능.

## 결정 2 — `AccessLogFilter` access log에도 memberId를 포함한다 (옵션 i)

### 배경

이슈 본문은 `AccessLogFilter` access log 처리를 명시적으로 다루지 않았다. 하지만 옵션 A만으로는 Controller/Service/Repository 로그에만 memberId가 찍히고, AccessLogFilter "요청 종료" 로그에는 빈 값이 찍힌다 — 운영 시 access log 한 줄로 "어떤 사용자가 어떤 요청을 했나"를 추적하지 못한다.

`AccessLogFilter`는 인증 실패 요청(401)이나 WHITELIST 외 요청의 access log도 남겨야 하므로 `JwtAuthenticationFilter`보다 바깥 Filter여야 한다. 따라서 `AccessLogFilter` finally 시점엔 `AuthenticationContext.clear()`가 이미 호출되어 `AuthenticationContext.getMemberId() == null`이다.

### 결정 내용

`JwtAuthenticationFilter`가 인증 성공 시 `request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, memberId)`를 호출하고, `AccessLogFilter` finally에서 `request.getAttribute(MEMBER_ID_ATTRIBUTE)`로 읽어 access log 출력 시점에만 잠깐 MDC를 채운 뒤 제거.

### 근거

- AccessLogFilter access log에 memberId가 찍히는 게 가장 운영 가치 큰 변화.
- `request.setAttribute`는 한 요청 처리 중 서버 메모리에만 살아있는 attribute 저장소로, ThreadLocal과 무관하므로 `AuthenticationContext.clear()` 이후에도 유효.
- Filter 순서를 뒤집어 `JwtAuthenticationFilter`를 바깥에 두는 옵션은 인증 실패 시 chain.doFilter를 호출하지 않아 access log 누락 발생 → 채택 불가.
- `AccessLogFilter`가 JWT를 직접 재파싱하는 옵션은 인증 로직 중복 → 채택 불가.

### 결과

- `AccessLogFilter`가 "AccessLogFilter용 memberId attribute"의 owner가 된다 (consumer-defined contract).
- 의존 방향: `security.filter.JwtAuthenticationFilter` → `common.log.filter.AccessLogFilter` (상수 참조). common이 더 일반적 위치라 자연스러움.
- "요청 시작" access log는 chain.doFilter 전이라 memberId가 빈 값 — 의도된 동작. 회고록에 명시.

## 결정 3 — 상수 `MEMBER_ID_ATTRIBUTE`는 `AccessLogFilter`에 정의한다

### 배경

attribute key 상수를 어디에 둘지 세 선택지가 있었다.
- `JwtAuthenticationFilter`(producer)에 정의: `common.log → security` 의존 발생, 방향 어색.
- `AccessLogFilter`(consumer)에 정의: `security → common.log` 의존, 자연스러움.
- 별도 상수 클래스(`common.web.RequestAttributes` 등) 신규: 단 하나의 키를 위한 신규 클래스는 YAGNI.

### 결정 내용

`AccessLogFilter.MEMBER_ID_ATTRIBUTE`로 정의. `JwtAuthenticationFilter`가 import해서 참조.

### 근거

- consumer-defined contract: "내 access log에 memberId 필요"라고 요구하는 쪽이 key의 의미를 정의.
- 의존 방향(`security → common.log`)이 일반성 관점에서 자연스러움.
- 별도 클래스는 키 하나일 때 과한 추상화.

### 결과

- attribute key 의미가 명확 (AccessLogFilter용임을 이름으로 표현).
- 미래에 다른 Filter에서도 memberId attribute가 필요해지면 그때 별도 상수 클래스로 추출 검토.

## 결정 4 — attribute key 값을 `"AccessLogFilter.memberId"`로 한다

### 배경

attribute key 값 후보:
- FQN: `"com.commerce.common.log.filter.AccessLogFilter.memberId"` — Spring 컨벤션, 충돌 없음. 단 패키지 이동 시 수동 갱신.
- `Class.getName() + ".memberId"` — 파일 이동/리네임 시 자동 갱신. 단 runtime 평가.
- 클래스명만: `"AccessLogFilter.memberId"` — 짧고 패키지 이동에 무관. 클래스 rename 시만 수동.
- 단순 키: `"memberId"` — 가장 짧지만 충돌 위험.

### 결정 내용

`"AccessLogFilter.memberId"` (클래스명 prefix 하드코딩).

### 근거

- 클래스명 prefix로 충돌 사실상 불가능 (Spring 내부 키도 보통 클래스 단위 namespace).
- 패키지 이동에 무관 (클래스 rename 시만 수동 갱신, IDE 리팩토링과 자연 연계).
- FQN보다 짧고 가독성 좋음.
- 단순 키는 다른 lib과 충돌 위험.

### 결과

- 키 값이 짧고 읽기 쉬움.
- 클래스 rename 시 상수 값도 같이 갱신해야 함 (한 줄). 컴파일 에러로 잡히진 않으니 회고록/문서에 컨벤션 명시.

## 결정 5 — `JwtAuthenticationFilter`를 `FilterRegistrationBean`으로 이전한다

### 배경

`JwtAuthenticationFilter`는 현재 `@Component`로 자동 등록되어 order가 Spring Boot 기본값(`Ordered.LOWEST_PRECEDENCE`)에 암묵적으로 의존한다. A+i 작업 자체는 현재 순서(TraceId +10 → AccessLog +20 → JwtAuth LOWEST)에서 정상 동작한다. 그러나 미래에 다른 `@Component` Filter가 추가되면 같은 LOWEST_PRECEDENCE에 충돌해 순서가 Bean name 알파벳순 등 암묵적 규칙에 의존하게 된다.

### 결정 내용

`@Component`를 제거하고 `JwtAuthenticationFilterConfig`(신규) 안에서 `FilterRegistrationBean`으로 등록. order = `Ordered.HIGHEST_PRECEDENCE + 30`. 같은 영역의 `TraceIdFilterConfig`/`AccessLogFilterConfig` 패턴과 일치.

### 근거

- 미래 Filter 추가 시 의도된 순서를 명시적으로 표현 가능.
- 같은 영역(Filter 등록 정책)을 또 만져야 한다면 이번에 한 번에 정리하는 게 효율적(재방문 비용 회피).
- 동작 변화 없는 refactor라 회귀 위험 낮음.

### 결과

- 모든 Filter가 명시적 order로 등록되는 정책 확립.
- `SecurityWebMvcTest`의 `@Import(JwtAuthenticationFilter.class)`를 `@Import(JwtAuthenticationFilterConfig.class)`로 변경 필요 (1줄).
- 이슈 본문에 없는 추가 변경이므로 회고록/PR에서 사유 기록.

## 결정 6 — Step과 commit을 목적별로 분리한다 (refactor + feat)

### 배경

이슈 한 건의 작업이지만 두 개의 다른 목적을 갖는다.
1. `JwtAuthenticationFilter` 등록 방식 통일 (refactor — 동작 무변화)
2. memberId MDC + access log 전파 추가 (feat — 동작 변화)

### 결정 내용

Step과 commit을 분리:
- Step 1 (commit type: `refactor`): `register-jwt-auth-filter-explicitly`
- Step 2 (commit type: `feat`): `propagate-memberid-mdc`
- Step 3 (commit type: `docs`): `sync-root-docs`
- Step 4 (commit type: `docs`): `write-retrospective`

### 근거

- 목적이 다른 변경은 분리한다는 컨벤션(commit-conventions.md, `feedback_separate_commits`).
- reviewer가 refactor의 회귀 없음을 단독으로 확인 가능, feat의 동작 변화를 단독으로 확인 가능.
- 한 PR로 묶어 운영 가치 단위로 머지 (4 commit 한 PR).

### 결과

- step 수 증가 (3 → 4)로 phase 진행이 길어짐.
- commit 메시지와 변경 범위가 목적별로 명확.
