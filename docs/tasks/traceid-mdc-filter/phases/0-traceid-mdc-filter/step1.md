# Step 1: traceid-mdc-filter

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도와 기존 패턴을 파악하라:

- `docs/tasks/traceid-mdc-filter/prd.md`
- `docs/tasks/traceid-mdc-filter/architecture.md`
- `docs/tasks/traceid-mdc-filter/adr.md`
- `docs/tasks/traceid-mdc-filter/api-spec.md`
- `src/main/resources/logback-spring.xml` — 콘솔 패턴과 `<mdc/>` provider 확인
- `src/main/java/com/commerce/common/log/MaskingMessageJsonProvider.java` — 같은 패키지의 기존 파일
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java` — OncePerRequestFilter 패턴 참고
- `src/test/java/com/commerce/security/SecurityWebMvcTest.java` — 통합 테스트 패턴 참고 (inner TestController, @WebMvcTest + @Import)

공통 컨텍스트가 더 필요하면:

- `docs/logging-conventions.md` §8 (MDC 운영 규칙)

## 작업

### 1. `TraceIdFilter` 신규 작성

파일 경로: `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java`

- `OncePerRequestFilter`를 상속한다
- `@Component` 붙이지 않는다 (FilterRegistrationBean으로만 등록)
- 상수:
  - `TRACE_ID_HEADER = "X-Trace-Id"`
  - `TRACE_ID_MDC_KEY = "traceId"`
  - `VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$")`
- `doFilterInternal` 구현:
  1. `request.getHeader(TRACE_ID_HEADER)` 읽기
  2. 값이 null이거나 `VALID_TRACE_ID` 패턴 불통과 → `UUID.randomUUID().toString()`
  3. `response.setHeader(TRACE_ID_HEADER, traceId)` — doFilter 전에 set
  4. `MDC.put(TRACE_ID_MDC_KEY, traceId)`
  5. `try { chain.doFilter(request, response); } finally { MDC.remove(TRACE_ID_MDC_KEY); }`

주의:
- `MDC.clear()` 금지. `MDC.remove(TRACE_ID_MDC_KEY)`만 호출한다. 이유: 향후 P3/P4에서 다른 MDC 키를 push하는 코드가 추가될 때 그 키들을 같이 날리는 위험을 차단하기 위함

### 2. `TraceIdFilterConfig` 신규 작성

파일 경로: `src/main/java/com/commerce/common/log/filter/TraceIdFilterConfig.java`

- `@Configuration` 붙임
- `FilterRegistrationBean<TraceIdFilter>` Bean 반환
- `bean.addUrlPatterns("/*")` — 모든 경로
- `bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10)`
- `new TraceIdFilter()`로 인스턴스 생성 (TraceIdFilter는 의존성 없음)

### 3. 단위 테스트: `TraceIdFilterTest`

파일 경로: `src/test/java/com/commerce/common/log/filter/TraceIdFilterTest.java`

`MockHttpServletRequest` / `MockHttpServletResponse` / `MockFilterChain`으로 직접 검증. `@Tag` 없음.

검증 시나리오:
1. incoming 헤더 없음 → 응답 헤더에 UUID 형식의 X-Trace-Id가 set됨
2. 유효한 incoming 헤더 (`existing-trace-123`) → 응답 헤더에 동일 값
3. 부적합 incoming 헤더 (65자 초과) → 새 UUID 발급
4. 부적합 incoming 헤더 (특수문자 포함, 예: `<script>`) → 새 UUID 발급
5. `chain.doFilter` 실행 중 `MDC.get(TRACE_ID_MDC_KEY)`가 set한 traceId와 일치 — `MockFilterChain`이나 Mockito `doAnswer`로 FilterChain 내부 MDC 상태 캡처해 검증
6. `doFilter` 완료 후 `MDC.get(TRACE_ID_MDC_KEY) == null`
7. `chain.doFilter`에서 `ServletException` 던지더라도 `MDC.get(TRACE_ID_MDC_KEY) == null` (finally 검증)

### 4. 통합 테스트: `TraceIdFilterIntegrationTest`

파일 경로: `src/test/java/com/commerce/common/log/filter/TraceIdFilterIntegrationTest.java`

`SecurityWebMvcTest.java:119` 패턴을 그대로 따른다. `@Tag` 없음.

```java
@WebMvcTest(controllers = TraceIdFilterIntegrationTest.TestController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import(TraceIdFilterConfig.class)
class TraceIdFilterIntegrationTest {

    @Autowired MockMvc mockMvc;

    @RestController
    static class TestController {
        @GetMapping("/__test__/trace")
        ResponseEntity<Void> probe() { return ResponseEntity.ok().build(); }
    }
}
```

검증 시나리오:
1. `GET /__test__/trace` 호출 → 응답 헤더에 `X-Trace-Id` 존재
2. 두 번 호출 → traceId가 서로 다름
3. `X-Trace-Id: existing-trace-123` 헤더 포함 요청 → 응답 헤더에 동일 값
4. `X-Trace-Id: <script>alert(1)</script>` 포함 요청 → 응답 헤더에 새 UUID (부적합 입력 차단)

## 수정 가능 경로

- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java` (신규)
- `src/main/java/com/commerce/common/log/filter/TraceIdFilterConfig.java` (신규)
- `src/test/java/com/commerce/common/log/filter/TraceIdFilterTest.java` (신규)
- `src/test/java/com/commerce/common/log/filter/TraceIdFilterIntegrationTest.java` (신규)
- `docs/tasks/traceid-mdc-filter/**` (task 문서)

## Acceptance Criteria

```bash
./gradlew test
./gradlew dockerTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 순서대로 실행한다.
2. `TraceIdFilterTest` 전체 시나리오 통과 확인.
3. `TraceIdFilterIntegrationTest` 전체 시나리오 통과 확인.
4. 빌드 경고/에러 없음 확인.
5. `architecture.md`의 설계 방향(Filter 위치, 등록 방식, MDC 키 책임 경계)을 따르는가 확인.
6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `MDC.clear()` 호출 금지. 이유: 향후 추가될 다른 MDC 키(userId, orderId 등)를 같이 날리는 위험
- `TraceIdFilter`에 `@Component` 부착 금지. 이유: FilterRegistrationBean과 중복 등록되어 Filter가 두 번 실행될 수 있음
- 외부 라이브러리 추가 금지. 이유: UUID는 java.util.UUID 표준 라이브러리로 충분
- 기존 테스트를 깨뜨리지 마라
