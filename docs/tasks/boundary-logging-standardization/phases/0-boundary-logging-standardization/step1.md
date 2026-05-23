# Step 1: access-log-filter

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/boundary-logging-standardization/prd.md`
- `/docs/tasks/boundary-logging-standardization/architecture.md`
- `/docs/tasks/boundary-logging-standardization/adr.md`
- `/docs/tasks/boundary-logging-standardization/api-spec.md`
- `/docs/tasks/boundary-logging-standardization/db-schema.md`
- `/src/main/java/com/commerce/common/log/filter/TraceIdFilter.java` — Filter 구현 패턴 참조
- `/src/main/java/com/commerce/common/log/filter/TraceIdFilterConfig.java` — FilterRegistrationBean 등록 패턴 참조
- `/src/test/java/com/commerce/common/log/filter/TraceIdFilterTest.java` — 단위 테스트 구성 참조
- `/src/test/java/com/commerce/common/log/filter/TraceIdFilterIntegrationTest.java` — 통합 테스트 구성 참조

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/logging-conventions.md` §3 (레이어별 로그 정책), §7 (메시지 작성 규칙)
- `/docs/architecture.md` HTTP 요청 처리 Filter 절

## 작업

`AccessLogFilter`와 `AccessLogFilterConfig`를 신규 작성하고, 단위 테스트와 MockMvc 통합 테스트를 추가한다.

### 신규 파일

1. `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`
   - `OncePerRequestFilter` 상속.
   - `@Slf4j`로 logger 주입.
   - `doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain)` 안에서:
     - 진입 시 `log.info("요청 시작 method={} path={}", request.getMethod(), request.getRequestURI())`.
     - `long startNanos = System.nanoTime()`로 시작 시각 저장.
     - `try { chain.doFilter(...); } finally { long durationMs = (System.nanoTime() - startNanos) / 1_000_000; log.info("요청 종료 status={} latency={}ms", response.getStatus(), durationMs); }` 구조.
   - 클래스 상단에 한 줄 주석으로 "path 제외 목록 미적용 (YAGNI). actuator 도입 또는 noisy 발견 시 추가 검토" 메모.
   - `@Component` 붙이지 않음 — FilterRegistrationBean으로만 등록.

2. `src/main/java/com/commerce/common/log/filter/AccessLogFilterConfig.java`
   - `@Configuration`.
   - `FilterRegistrationBean<AccessLogFilter>`를 빈으로 등록.
   - `urlPatterns`: `/*`.
   - `order`: `Ordered.HIGHEST_PRECEDENCE + 20`.
   - 생성자에서 `AccessLogFilter`를 주입.

3. `src/test/java/com/commerce/common/log/filter/AccessLogFilterTest.java` — 단위 테스트
   - `LoggerFactory`로 `AccessLogFilter` logger를 가져와 `ListAppender<ILoggingEvent>`를 부착하는 방식 또는 `MockHttpServletRequest`/`MockHttpServletResponse`로 직접 호출.
   - 케이스:
     - 정상 200 → 요청 시작 INFO + 요청 종료 INFO 2건, 종료 메시지에 `status=200`과 `latency=` 포함.
     - 4xx (예: status 404) → 요청 종료에 `status=404` 포함.
     - 5xx (예: status 500) → 요청 종료에 `status=500` 포함.
     - chain.doFilter에서 RuntimeException 전파 시에도 종료 로그가 남는다 (`try-finally` 검증).
     - 메시지가 placeholder `{}`로 작성되어 있는지 확인 (literal concatenation 사용 금지).

4. `src/test/java/com/commerce/common/log/filter/AccessLogFilterIntegrationTest.java` — 통합 테스트
   - `@SpringBootTest` + `@AutoConfigureMockMvc`.
   - 임의의 컨트롤러 호출(예: 기존 ProductController의 단순 GET 또는 `@TestConfiguration`으로 `/test-endpoint` 추가) 후 `MockMvc`로 호출.
   - ListAppender로 `AccessLogFilter`의 로그를 캡처해 2건 발생 확인.
   - TraceIdFilter가 먼저 실행되어 MDC에 traceId가 있는 상태에서 액세스 로그가 작성되는지 확인 (logback 패턴 부착 검증은 logback 단위 — 본 테스트는 Filter 실행 순서만 확인하면 충분).

### 메시지 형식 고정

다음 두 메시지를 정확히 사용한다.

```java
log.info("요청 시작 method={} path={}", method, path);
log.info("요청 종료 status={} latency={}ms", status, durationMs);
```

traceId, memberId는 메시지 인자로 추가하지 않는다 — logback 패턴이 MDC를 통해 자동 부착하므로 중복이다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/architecture.md`의 Filter 디렉토리 위치(`common/log/filter/`)를 따랐는가?
   - TraceIdFilter와 동일한 등록 패턴(FilterRegistrationBean, no `@Component`)을 사용했는가?
   - order = `Ordered.HIGHEST_PRECEDENCE + 20`인가? (TraceIdFilter +10 다음)
   - 메시지가 한국어 + placeholder `{}`인가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- AccessLogFilter에 `@Component`를 붙이지 마라. 이유: FilterRegistrationBean과 병용 시 Filter가 두 번 실행되며, TraceIdFilter도 같은 정책이다 (P2 작업의 결정).
- 메시지에 traceId/memberId를 명시적으로 인자 추가하지 마라. 이유: logback 콘솔 패턴 `[traceId=%X{traceId:-} memberId=%X{memberId:-}]`이 MDC를 통해 자동 부착한다. 중복이고 일관성을 해친다.
- ContentCachingRequestWrapper로 body 캐싱을 도입하지 마라. 이유: 본 작업 범위 밖이며, 메모리 부담이 있다 (ADR/PRD 제외 범위 참조).
- path 제외 목록(`/actuator/**`, `/favicon.ico`)을 사전 추가하지 마라. 이유: YAGNI. 현재 noise가 없고, 사전 추가 시 dead code 가능성이 있다. 클래스 주석 한 줄로만 메모한다.
- TraceIdFilter에 액세스 로그 코드를 추가하지 마라. 이유: 책임 분리 (ADR 결정 1).
- 문자열 concatenation 로깅을 쓰지 마라 (`log.info("요청 시작 " + path)` 금지). 이유: 컨벤션 §7.
- 기존 테스트를 깨뜨리지 마라.
