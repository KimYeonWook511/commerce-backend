# 태스크 PRD

## 태스크명

- `traceid-mdc-filter`

## 배경

- 이슈 #127(로깅 규칙 문서)과 #128(logback 설정)이 머지되어 MDC 운영 정책(`docs/logging-conventions.md §8`)과 인프라(`logback-spring.xml`)가 모두 준비된 상태다.
- `logback-spring.xml`의 콘솔 패턴 `[traceId=%X{traceId:-} userId=%X{userId:-}]`과 파일 JSON `<mdc/>` provider가 MDC를 읽도록 이미 구성되어 있으나, MDC를 채워주는 Filter가 없어 traceId가 항상 빈 문자열로 출력된다.
- 동시 요청이 들어오면 어느 요청의 로그인지 추적이 불가능하다. 장애 발생 시 요청 단위 로그 흐름을 따라갈 수 없다.
- 이슈 #129로 요청별 traceId를 MDC에 push하는 Filter를 도입한다.

## 목표

- 모든 HTTP 요청에 고유 traceId(UUID)를 발급해 요청 단위 로그 추적을 가능하게 한다.
- 응답 헤더 `X-Trace-Id`를 통해 클라이언트(또는 운영자)가 특정 요청의 traceId를 확인할 수 있게 한다.
- 외부 시스템에서 전달한 `X-Trace-Id` 값을 재사용해 향후 분산 추적과의 호환을 확보한다.

## 범위

### 포함 범위

- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java` 신규
- `src/main/java/com/commerce/common/log/filter/TraceIdFilterConfig.java` 신규 (FilterRegistrationBean 등록)
- 단위 테스트: `src/test/java/com/commerce/common/log/filter/TraceIdFilterTest.java`
- 통합 테스트: `src/test/java/com/commerce/common/log/filter/TraceIdFilterIntegrationTest.java`
- 루트 `docs/architecture.md` 로깅/요청 처리 절 보강

### 제외 범위

- userId MDC push — 인증 흐름과 결합되므로 P3/P4 후속 작업 책임
- `@Async`, Kafka consumer, `@TransactionalEventListener`에서의 traceId 전파 — Epic 후속 작업
- 외부 호출 시 X-Trace-Id out-bound 전파
- MdcKeys 상수 클래스 추출 — P3에서 키가 늘어나면 그때
- CORS `Access-Control-Expose-Headers` 설정 — 프로젝트가 CORS 미사용
- logback-spring.xml 수정 — 이미 `%X{traceId:-}` / `<mdc/>` 준비 완료

## 주요 시나리오

- 클라이언트가 `GET /products`를 호출하면 응답 헤더에 `X-Trace-Id: 550e8400-...`가 포함된다.
- 인증이 필요한 `POST /orders`에서 JWT 오류로 401이 반환될 때도 `X-Trace-Id`가 응답에 포함된다.
- 클라이언트가 `X-Trace-Id: my-trace-id`를 요청에 보내면 응답 헤더에도 동일 값이 돌아온다(재사용).
- 악성 값(`X-Trace-Id: <script>alert(1)</script>`)이 들어오면 무시하고 새 UUID를 발급한다.
- 동시에 두 요청이 들어오면 콘솔 로그에 서로 다른 `[traceId=<UUID1>]`, `[traceId=<UUID2>]`가 출력된다.

## 요구사항

- 모든 HTTP 요청에 UUID v4(36자, 하이픈 포함) traceId 발급
- 응답 헤더 `X-Trace-Id`에 traceId 추가
- incoming `X-Trace-Id` 헤더가 유효하면 재사용, 유효하지 않으면 신규 발급
- incoming 검증 기준: `^[A-Za-z0-9_-]{1,64}$` 패턴 일치 여부
- MDC `traceId` 키에 push, 요청 종료 시 `MDC.remove("traceId")` 호출
- Filter order: `Ordered.HIGHEST_PRECEDENCE + 10` (JwtAuthenticationFilter의 기본 LOWEST_PRECEDENCE보다 먼저 실행)
- 모든 URL(`/*`) 적용

## 제약사항

- `docs/logging-conventions.md §8` 정책 준수 (MDC 키명 `traceId`, `userId`)
- `MDC.clear()` 금지 — `MDC.remove("traceId")`로 단일 키만 제거. P3/P4에서 추가될 userId, orderId 등을 같이 날리는 위험을 차단한다.
- TraceIdFilter에 `@Component` 붙이지 않음 — FilterRegistrationBean으로만 등록해 중복 등록 방지
- 외부 의존성 추가 없음 (UUID는 java.util.UUID 표준 라이브러리)
- `logback-spring.xml` 변경 없음 — Filter 도입 전에는 `[traceId= userId=]`로 출력되고, 도입 후 `[traceId=<UUID> userId=]`가 된다. 별도 logback 설정 불필요
