# Step 2: propagate-memberid-mdc

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도와 기존 패턴을 파악하라:

- `docs/tasks/memberid-mdc-propagation/prd.md`
- `docs/tasks/memberid-mdc-propagation/architecture.md` — 데이터 흐름 다이어그램
- `docs/tasks/memberid-mdc-propagation/adr.md` — 결정 1~4
- `docs/logging-conventions.md` — §5(memberId 식별), §8(MDC 운영)
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java` — Step 1에서 수정된 상태 (`@Component` 제거)
- `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java` — 수정 대상
- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java` — MDC 패턴 참고 (`MDC.remove` 단일 키)
- `src/main/java/com/commerce/security/context/AuthenticationContext.java` — clear 동작 확인
- `src/test/java/com/commerce/security/SecurityWebMvcTest.java` — Step 1에서 `@Import` 변경된 상태, 시나리오 확장 대상

## 작업

### 1. `AccessLogFilter` 수정

파일: `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`

- 클래스 상단에 상수 추가:
  ```java
  public static final String MEMBER_ID_ATTRIBUTE = "AccessLogFilter.memberId";
  ```
- import 추가: `import org.slf4j.MDC;`
- `doFilterInternal`의 finally 블록 수정. 기존:
  ```java
  } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      log.info("요청 종료 status={} latency={}ms", status, durationMs);
  }
  ```
  변경 후:
  ```java
  } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      Object memberId = request.getAttribute(MEMBER_ID_ATTRIBUTE);
      boolean pushed = false;
      if (memberId != null) {
          MDC.put("memberId", String.valueOf(memberId));
          pushed = true;
      }
      try {
          log.info("요청 종료 status={} latency={}ms", status, durationMs);
      } finally {
          if (pushed) {
              MDC.remove("memberId");
          }
      }
  }
  ```
- "요청 시작" 로그(L28 부근)는 변경하지 않는다. chain.doFilter 전이라 인증 안 끝났으므로 memberId가 없는 게 정상.

### 2. `JwtAuthenticationFilter` 수정

파일: `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`

- import 추가:
  - `import com.commerce.common.log.filter.AccessLogFilter;`
  - `import org.slf4j.MDC;`
- 인증 성공 직후(`AuthenticationContext.set(...)` 다음 줄)에 추가:
  ```java
  AuthenticationContext.set(principal.getMemberId(), principal.getRole());
  MDC.put("memberId", String.valueOf(principal.getMemberId()));
  request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, principal.getMemberId());
  ```
- finally 블록 안 `AuthenticationContext.clear()` 옆에 `MDC.remove("memberId")` 추가:
  ```java
  } finally {
      // 반드시 정리
      AuthenticationContext.clear();
      MDC.remove("memberId");
  }
  ```
- 기존 주석은 그대로 유지 (`// 반드시 정리`).

### 3. 단위 테스트 신규: `JwtAuthenticationFilterTest`

파일: `src/test/java/com/commerce/security/filter/JwtAuthenticationFilterTest.java`

`MockHttpServletRequest` / `MockHttpServletResponse` / `MockFilterChain` 사용. chain 내부 MDC 상태 캡처는 `MockFilterChain`의 inner Filter 또는 Mockito `doAnswer`로 구현. `@Tag` 없음.

`TokenAuthenticationService`는 `Mockito.mock` 또는 `@MockBean`이 아닌 직접 stub 객체로 주입(@WebMvcTest가 아닌 순수 단위 테스트이므로). `ObjectMapper`는 실제 인스턴스 사용 가능.

시나리오:
1. **인증 성공 시 MDC.memberId set**: 토큰 검증 성공 stub → chain.doFilter 실행 중 `MDC.get("memberId")` 값이 principal.memberId의 String 변환과 일치
2. **인증 성공 시 attribute set**: 동일 시나리오에서 chain.doFilter 실행 중 `request.getAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE)` 값이 principal.memberId(Long)와 일치
3. **finally에서 MDC.remove**: doFilter 완료 후 `MDC.get("memberId") == null`
4. **토큰 누락**: Authorization 헤더 없는 요청 → `MDC.get("memberId") == null`, `request.getAttribute(...) == null`, 401 응답
5. **인증 실패 (CustomException)**: 토큰 검증에서 CustomException throw → catch 후 401 응답, finally에서 MDC 잔류 없음
6. **WHITELIST 경로 (`/products`)**: chain.doFilter만 호출, MDC.put 안 됨, attribute 안 set
7. **chain.doFilter 예외**: 인증 성공 후 chain.doFilter가 예외를 throw해도 finally에서 MDC.remove 보장 (예외는 그대로 전파)
8. **스레드 풀 재사용 시나리오**: 테스트 setup에서 사전 `MDC.put("memberId", "prev-value")`로 잔류 상태 만들고 인증 성공 요청 처리 → chain.doFilter 시점에 MDC.memberId가 새 값으로 set, finally 이후에는 `MDC.get("memberId") == null` (이전 값 잔류 없음, 새 값 잔류 없음). `@AfterEach`에서 `MDC.clear()`로 테스트 격리.

### 4. 단위 테스트 신규: `AccessLogFilterTest`

파일: `src/test/java/com/commerce/common/log/filter/AccessLogFilterTest.java`

`MockHttpServletRequest` / `MockHttpServletResponse` / `MockFilterChain` 사용. `@Tag` 없음. MDC 검증은 Logback `ListAppender`로 LoggingEvent의 MDC snapshot을 캡처하거나, `MockFilterChain`의 inner Filter로 시점 캡처.

시나리오:
1. **attribute 없는 요청**: `MDC.get("memberId") == null` 상태, attribute 없는 요청 처리 → "요청 종료" 로그 LoggingEvent의 MDC에 memberId 없음, 요청 종료 후 `MDC.get("memberId") == null`
2. **attribute set된 요청**: setup에서 `request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, 42L)` → "요청 종료" 로그 LoggingEvent의 MDC에 `memberId=42`가 있음, 요청 종료 후 `MDC.get("memberId") == null` (remove 호출 검증)
3. **chain 예외 시에도 MDC.remove**: attribute set된 상태에서 chain.doFilter가 예외 throw → 예외 전파되지만 `MDC.get("memberId") == null` 보장
4. **MDC 잔류 없음 (일반)**: 시나리오 1, 2 모두 종료 후 잔류 없음

`@AfterEach`에서 `MDC.clear()`로 테스트 격리.

### 5. `SecurityWebMvcTest` 시나리오 확장

파일: `src/test/java/com/commerce/security/SecurityWebMvcTest.java`

기존 시나리오는 유지. 다음을 추가:

- 인증 요청 처리 중 Controller에서 `MDC.get("memberId")` 값을 캡처해 응답으로 노출하는 TestController endpoint 추가 (예: `/test/mdc-member-id` → `MDC.get("memberId")` 그대로 반환)
- 인증 성공 시 해당 endpoint 응답이 `"<memberId>"`와 일치하는지 검증
- 요청 종료 후 (테스트 메서드 끝에서) `MDC.get("memberId") == null` 검증
- WHITELIST 경로(`/products`) 호출 시 같은 endpoint 로직으로 검증할 수는 없으므로, 별도 시나리오에서 `MDC.get("memberId") == null` 검증 (또는 기존 `whitelistEndpoint_whenRequested_doNotAuthenticateToken`에 검증 추가)

`@AfterEach`에서 `MDC.clear()`로 격리.

## 수정 가능 경로

- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`
- `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`
- `src/test/java/com/commerce/security/filter/JwtAuthenticationFilterTest.java` (신규)
- `src/test/java/com/commerce/common/log/filter/AccessLogFilterTest.java` (신규)
- `src/test/java/com/commerce/security/SecurityWebMvcTest.java`
- `docs/tasks/memberid-mdc-propagation/**` (task 문서, 필요 시)

## Acceptance Criteria

```bash
./gradlew test
```

이 step은 인증/권한 경계 변경 및 공통 응답/로깅 변경에 해당하므로 전체 테스트(`./gradlew test`) 실행이 필수.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. `JwtAuthenticationFilterTest`의 8개 시나리오 전체 통과 확인.
3. `AccessLogFilterTest`의 4개 시나리오 전체 통과 확인.
4. `SecurityWebMvcTest` 기존 시나리오 회귀 없음 + 추가 시나리오 통과 확인.
5. `MDC.clear()` 호출 없음 확인:
   ```bash
   rg "MDC\.clear\(\)" src/main
   ```
   예상 출력: 없음 (테스트 setup의 `@AfterEach`는 src/test이므로 별개).
6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `MDC.clear()` 호출 금지. 이유: `traceId` 등 다른 MDC 키를 동시에 날리는 위험. `TraceIdFilter`(`src/main/java/com/commerce/common/log/filter/TraceIdFilter.java:38-40`)도 동일 패턴을 따른다.
- `AuthenticationContext`에 SLF4J/MDC 의존 추가 금지. 이유: 인증 도메인 컨텍스트 클래스가 로깅 라이브러리를 알지 않는다 (ADR 결정 3 trade-off 회피).
- "요청 시작" access log를 수정해 인증 후 시점으로 옮기지 마라. 이유: chain.doFilter 전 시점 access log는 인증 실패/예외 진단에 필요하다 (의도된 동작, ADR 결정 2).
- `request.setAttribute` 대신 다른 ThreadLocal 신규 클래스 만들지 마라. 이유: YAGNI, request attribute로 충분.
- `JwtAuthenticationFilter`의 등록 방식이나 Filter chain 순서를 추가 변경하지 마라. 이유: Step 1에서 이미 처리된 범위.
- 기존 테스트를 깨뜨리지 마라.
