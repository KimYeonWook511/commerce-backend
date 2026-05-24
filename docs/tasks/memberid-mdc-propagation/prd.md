# 태스크 PRD

## 태스크명

- `memberid-mdc-propagation`

## 배경

- 이슈 #145는 Epic #133 "운영용 로깅 체계 도입"의 P3/P4 누락분을 보완한다.
- P1(#128 logback)에서 콘솔 패턴 `memberId=%X{memberId:-}`과 JSON `<mdc/>` provider가 MDC를 읽도록 이미 준비됐다.
- P2(#129 traceId Filter)에서 `MDC.clear()` 대신 `MDC.remove("traceId")`를 쓴 이유가 "후속 memberId push를 위한 자리 비움"이었으나, P3/P4 어느 작업에서도 실제 push가 추가되지 않았다.
- 현재 `JwtAuthenticationFilter`(`src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java:67`)가 `AuthenticationContext.set(memberId, role)`만 호출하고 `MDC.put("memberId", ...)`를 호출하지 않아, prod 모든 로그의 `memberId=` 필드가 빈 값으로 출력된다.
- 추가로 `AccessLogFilter`(`src/main/java/com/commerce/common/log/filter/AccessLogFilter.java:26,34`)의 "요청 시작/종료" access log도 memberId가 빈 값이다. AccessLogFilter는 JwtAuthenticationFilter보다 바깥 Filter라 인증 결과 MDC를 그대로 읽지 못한다.
- 또한 `JwtAuthenticationFilter`는 `@Component`로 자동 등록되어 order가 `LOWEST_PRECEDENCE` 기본값에 암묵적으로 의존한다. 미래에 다른 `@Component` Filter가 추가되면 같은 LOWEST_PRECEDENCE에 충돌해 순서가 Bean name 알파벳순 등 암묵적 규칙에 의존하게 된다.

## 목표

- 인증된 사용자의 `memberId`를 모든 application 로그 채널(도메인 로그 MDC + access log)에 노출해 "어떤 사용자가 어떤 요청을 했나"를 한 줄로 추적 가능하게 한다.
- `JwtAuthenticationFilter`도 `TraceIdFilter`/`AccessLogFilter`와 동일하게 `FilterRegistrationBean` + 명시 order 패턴으로 통일해 Filter 등록 정책의 일관성과 미래 안정성을 확보한다.

## 범위

### 포함 범위

- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java` 수정 — `@Component` 제거, 인증 성공 시 `MDC.put("memberId")` + `request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, memberId)`, finally에서 `MDC.remove("memberId")`
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilterConfig.java` 신규 — `FilterRegistrationBean` 등록 (order `HIGHEST_PRECEDENCE + 30`)
- `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java` 수정 — `MEMBER_ID_ATTRIBUTE` 상수, finally에서 attribute 읽어 MDC에 잠깐 채우고 access log 출력 후 remove
- 단위 테스트: `JwtAuthenticationFilterTest`, `AccessLogFilterTest`
- 통합 테스트 확장: `SecurityWebMvcTest` — `@Import` 변경, MDC 검증 시나리오 추가
- 루트 `docs/architecture.md` HTTP 요청 처리/로깅 절 보강

### 제외 범위

- 별도 `MemberIdMdcFilter` 클래스 분리 — 인증 컨텍스트와 강결합이라 `JwtAuthenticationFilter` 내부에 두는 게 자연스러움 (이슈 본문 결정 유지)
- 비인증 요청(WHITELIST) 처리 — 비인증이므로 memberId가 null인 것이 정상
- `@Async`, Kafka consumer, `@TransactionalEventListener`에서의 MDC 전파 — Epic 후속 작업
- Authorization 헤더 마스킹, GDPR 보관 기간 등 컨벤션 §5 다른 항목 — 별도 후속 작업
- MSA 분리, Gateway 인증 위임 — 시스템 구조 변경으로 별개 주제

## 주요 시나리오

- 클라이언트가 인증된 요청 `GET /orders`(Bearer 토큰 유효)를 호출하면:
  - Controller/Service/Repository 로그에 `[traceId=abc memberId=42]`로 찍힌다.
  - AccessLogFilter "요청 종료" access log에도 `[traceId=abc memberId=42]`로 찍힌다.
- 클라이언트가 비인증 경로 `GET /products`를 호출하면:
  - 모든 로그에 `[traceId=def memberId=]`로 memberId가 빈 값으로 유지된다.
- 클라이언트가 인증 실패 요청 `GET /orders`(토큰 누락)를 호출하면:
  - AccessLogFilter "요청 종료" 로그에도 `[traceId=ghi memberId=]`로 빈 값.
  - 다음 요청 처리 시 이전 요청의 memberId가 잔류하지 않는다.

## 요구사항

- 인증 성공 시 `MDC.put("memberId", String.valueOf(principal.getMemberId()))` 호출
- 인증 성공 시 `request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, principal.getMemberId())` 호출
- 요청 종료 시 `MDC.remove("memberId")` 호출 (finally 보장)
- `AccessLogFilter`가 finally에서 `request.getAttribute(MEMBER_ID_ATTRIBUTE)`를 읽어 access log 출력 시점에만 MDC를 잠깐 채움
- 모든 Filter가 명시적 order로 등록됨 (`TraceIdFilter` +10, `AccessLogFilter` +20, `JwtAuthenticationFilter` +30)
- 단위/통합 테스트가 스레드 풀 재사용 시나리오까지 통과
- `./gradlew test` 통과

## 제약사항

- `docs/logging-conventions.md` §5(memberId 식별), §8(MDC 운영) 정책 준수
- **금지**: `MDC.clear()` — `MDC.remove("memberId")`만 호출. 이유: traceId 등 다른 MDC 키를 함께 날리는 위험. `TraceIdFilter`도 동일 패턴(`MDC.remove("traceId")`).
- **금지**: `AuthenticationContext`에 SLF4J/MDC 의존 추가 — 인증 도메인 컨텍스트 클래스가 로깅 라이브러리를 알지 않는다.
- 의존 방향: `security.filter.JwtAuthenticationFilter` → `common.log.filter.AccessLogFilter` (상수 참조). 반대 방향(`common.log` → `security`)은 어색하므로 피한다.
- `JwtAuthenticationFilter`의 기존 인증/인가 로직과 예외 처리 흐름은 변경하지 않는다 (회귀 위험 차단).
