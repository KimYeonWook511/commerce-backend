# 태스크 아키텍처

## 개요

이번 태스크는 HTTP 요청 진입 시 traceId를 MDC에 주입하고 응답 후 제거하는 `OncePerRequestFilter`를 추가한다. application 코드(Service, Repository, Controller)에는 영향을 주지 않는 횡단 관심사(cross-cutting concern) 작업이다.

`logback-spring.xml`의 콘솔 패턴(`[traceId=%X{traceId:-} userId=%X{userId:-}]`)과 파일 JSON `<mdc/>` provider가 이미 MDC를 읽도록 구성되어 있어, Filter만 추가되면 즉시 모든 로그에 traceId가 반영된다.

## 변경 대상

- **새 파일 (인프라 코드)**: `src/main/java/com/commerce/common/log/filter/`
  - `TraceIdFilter.java` — MDC push/remove, X-Trace-Id 응답 헤더 추가, incoming 헤더 검증·재사용
  - `TraceIdFilterConfig.java` — FilterRegistrationBean 등록, order 부여
- **새 파일 (테스트)**: `src/test/java/com/commerce/common/log/filter/`
  - `TraceIdFilterTest.java` — 단위 테스트 (MockHttpServletRequest/Response)
  - `TraceIdFilterIntegrationTest.java` — MockMvc 통합 테스트
- **변경 없는 파일**:
  - `src/main/resources/logback-spring.xml` — 이미 MDC 읽도록 구성됨
  - `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java` — order 그대로 유지
  - `src/main/java/com/commerce/common/config/WebConfig.java` — 손대지 않음

## 설계 방향

### Filter 위치

`com.commerce.common.log.filter` 패키지에 위치한다. 같은 패키지에 `MaskingMessageJsonProvider`가 이미 있어 로깅 인프라 구성 요소를 한 곳에 응집한다.

### Filter 등록

`@Component`를 붙이지 않고 `FilterRegistrationBean<TraceIdFilter>`으로 명시 등록한다. order 값을 직접 지정하기 위해서이며, `@Component`와 `FilterRegistrationBean` 병행 시 이중 등록 위험이 있으므로 Config Bean 방식으로 통일한다.

### Filter 실행 순서

| Filter | Order |
|--------|-------|
| **TraceIdFilter** | `Ordered.HIGHEST_PRECEDENCE + 10` |
| JwtAuthenticationFilter | `Ordered.LOWEST_PRECEDENCE` (Spring Boot 기본) |

TraceIdFilter가 먼저 실행되므로 JWT 인증 실패 로그에도 traceId가 포함된다. `+ 10` 여유는 향후 TraceIdFilter보다 먼저 실행되어야 할 Filter(예: 에러 처리, 요청 파싱)를 위한 공간이다.

### MDC 키 책임 경계

TraceIdFilter는 자신이 push한 키(`traceId`)만 `MDC.remove`로 제거한다. `MDC.clear()`는 사용하지 않는다. 이유: 향후 P3/P4에서 인터셉터·AOP가 `userId`, `orderId` 등 다른 키를 push할 경우, TraceIdFilter의 `finally`에서 `MDC.clear()`를 호출하면 그 키들까지 모두 날리게 된다.

### incoming traceId 처리

```
요청 진입
  ├─ X-Trace-Id 헤더 없음     → UUID.randomUUID().toString() 발급
  ├─ 헤더 존재 + 패턴 통과    → 헤더 값 재사용
  └─ 헤더 존재 + 패턴 불통과  → UUID.randomUUID().toString() 신규 발급
            ↓
  MDC.put("traceId", traceId)
  response.setHeader("X-Trace-Id", traceId)
  chain.doFilter(req, res)
            ↓ (finally)
  MDC.remove("traceId")
```

검증 패턴: `^[A-Za-z0-9_-]{1,64}$` — UUID 형식 포함, 로그 인젝션 차단.

## 데이터 흐름

```
HTTP Request
  ↓
TraceIdFilter (HIGHEST_PRECEDENCE + 10)
  ├─ traceId 결정 (incoming 재사용 or UUID 신규 발급)
  ├─ MDC.put("traceId", traceId)
  ├─ response.setHeader("X-Trace-Id", traceId)
  ↓
JwtAuthenticationFilter (LOWEST_PRECEDENCE)
  ├─ 인증 성공 → AuthenticationContext.set(memberId, role)
  └─ 인증 실패 → unauthorized() 응답 (X-Trace-Id 헤더 유지됨)
  ↓
DispatcherServlet → Controller → Service → ...
  ↓
HTTP Response (X-Trace-Id 헤더 포함)
  ↓
TraceIdFilter finally
  └─ MDC.remove("traceId")
```

## 예외 및 실패 처리

- **chain.doFilter에서 예외 발생**: `try-finally` 구조로 예외와 무관하게 `MDC.remove`가 실행된다. 예외는 상위로 전파되어 Spring의 에러 처리 흐름을 따른다.
- **응답 이미 commit 후 헤더 set**: Filter가 가장 먼저 실행되고 `doFilter` 전에 `setHeader`를 호출하므로, 응답 commit 전에 헤더가 set된다. commit 후 setHeader 실패 케이스는 발생하지 않는다.
- **GlobalExceptionHandler 개입**: 헤더 reset 없음(확인됨). 에러 응답에도 X-Trace-Id 유지.
- **UUID 충돌**: UUID v4 충돌 확률은 무시 가능한 수준 (~2^122분의 1).

## 테스트 포인트

- incoming 헤더 없음 → 새 UUID 발급, 응답 헤더 set, MDC push, finally remove
- incoming 헤더 유효 → 동일 값 재사용
- incoming 헤더 부적합 → 새 UUID 발급
- `chain.doFilter` 중 MDC.get("traceId")가 set한 값과 일치
- `doFilter` 완료 후 MDC.get("traceId") == null
- `chain.doFilter`에서 예외 발생 시도 MDC.remove 호출됨
- MockMvc 통합: endpoint 호출 시 응답 헤더 X-Trace-Id 존재
- MockMvc 통합: 두 요청의 traceId가 서로 다름
