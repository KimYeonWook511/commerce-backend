# 태스크 아키텍처

## 개요

- HTTP 요청 진입 Filter chain에 인증 결과(memberId)를 MDC와 request attribute 두 채널로 전파하는 협업을 추가한다.
- `JwtAuthenticationFilter` 등록 방식을 `FilterRegistrationBean`으로 통일해 모든 Filter가 명시적 order를 갖는 정책을 확립한다.

## 변경 대상

- `security.filter.JwtAuthenticationFilter` — `@Component` 제거, 인증 성공 시 MDC push + attribute set
- `security.filter.JwtAuthenticationFilterConfig` (신규) — `FilterRegistrationBean` 등록
- `common.log.filter.AccessLogFilter` — `MEMBER_ID_ATTRIBUTE` 상수, finally에서 attribute 읽어 MDC 잠깐 채움

## 설계 방향

### 책임 분리

- `JwtAuthenticationFilter`: 인증과 인증 결과 전파(MDC + attribute)를 한 곳에서 책임진다. 별도 Filter 분리하지 않는 이유는 인증 컨텍스트와 강결합 (이슈 본문 결정 유지).
- `AccessLogFilter`: access log의 owner. memberId를 자기 로그에 필요로 하므로 `MEMBER_ID_ATTRIBUTE` 상수의 owner도 자신이 된다 — consumer-defined contract 패턴.
- `JwtAuthenticationFilter`가 `AccessLogFilter.MEMBER_ID_ATTRIBUTE`를 참조 (`security.filter` → `common.log.filter` 방향).

### Filter 등록 정책 통일

| Filter | 등록 방식 | order |
|---|---|---|
| `TraceIdFilter` | `FilterRegistrationBean` | `HIGHEST_PRECEDENCE + 10` |
| `AccessLogFilter` | `FilterRegistrationBean` | `HIGHEST_PRECEDENCE + 20` |
| `JwtAuthenticationFilter` (변경 후) | `FilterRegistrationBean` | `HIGHEST_PRECEDENCE + 30` |

기존: `JwtAuthenticationFilter`만 `@Component` 자동 등록(LOWEST_PRECEDENCE 기본값) → 미래 `@Component` Filter 추가 시 충돌 위험.
변경 후: 모든 Filter가 명시적 order. 미래 Filter 추가 시 의도된 위치 명시 가능.

## 데이터 흐름

### 인증된 요청 (예: `GET /orders` + 유효 Bearer 토큰)

```
[요청 진입]
  → TraceIdFilter
      MDC.put("traceId", "abc-123")
      → AccessLogFilter
          log.info("요청 시작 ...")           [MDC: traceId=abc-123]
          → JwtAuthenticationFilter
              AuthenticationContext.set(42, ROLE_USER)
              MDC.put("memberId", "42")
              request.setAttribute(MEMBER_ID_ATTRIBUTE, 42L)
              → DispatcherServlet → Controller
                  log.info("주문 조회 ...")    [MDC: traceId=abc-123, memberId=42]
              ← return
              finally:
                AuthenticationContext.clear()
                MDC.remove("memberId")        [MDC: traceId=abc-123]
          ← return
          finally:
            Long memberId = request.getAttribute(MEMBER_ID_ATTRIBUTE)   // → 42L
            MDC.put("memberId", "42")
            log.info("요청 종료 ...")          [MDC: traceId=abc-123, memberId=42]
            MDC.remove("memberId")             [MDC: traceId=abc-123]
      ← return
      finally:
        MDC.remove("traceId")                  [MDC: {}]
[응답 송신]
```

### 비인증/WHITELIST 요청 (예: `GET /products`)

```
JwtAuthenticationFilter는 WHITELIST 분기에서 chain.doFilter만 호출 (MDC.put, attribute set 안 함)
→ AccessLogFilter finally에서 request.getAttribute(...) == null → MDC.put 안 함 → access log의 memberId 빈 값
```

### 인증 실패 (예: 토큰 누락 또는 부적합 토큰)

```
JwtAuthenticationFilter
  토큰 누락: unauthorized() → 401 응답, chain.doFilter 호출 안 함, MDC.put 안 됨
  토큰 검증 실패: catch에서 unauthorized() → 401, finally에서 MDC.remove (안전망, MDC.put이 도달 안 했어도 NPE 없음)
→ AccessLogFilter finally에서 attribute null → MDC.put 안 함 → access log의 memberId 빈 값
```

## 예외 및 실패 처리

- `JwtAuthenticationFilter` finally는 인증 실패/예외 경로에서도 항상 `AuthenticationContext.clear()`와 `MDC.remove("memberId")`를 호출한다. 스레드 풀 재사용 시 다음 요청에 잔류 없음.
- `AccessLogFilter` finally의 MDC put/remove는 try-finally로 묶어 `log.info` 호출 자체에서 예외가 발생해도 `MDC.remove`가 보장된다.
- `MDC.remove`는 없는 키 제거 시 NPE 없음. MDC.put이 도달하지 못한 경로에서도 안전.
- `MDC.clear()` 호출 금지 — `traceId` 등 다른 MDC 키를 동시에 날리는 위험.

## 테스트 포인트

- `JwtAuthenticationFilterTest` (신규 단위 테스트):
  - 인증 성공 시 chain.doFilter 실행 중 MDC.memberId가 set됨 + request attribute가 set됨
  - finally에서 MDC.remove 호출
  - 토큰 누락 / 인증 실패 / WHITELIST / chain 예외 / 스레드 풀 재사용 시나리오에서 MDC 잔류 없음
- `AccessLogFilterTest` (신규 단위 테스트):
  - attribute 있는 요청 → access log 시점에 MDC.memberId set + 종료 후 remove
  - attribute 없는 요청 → MDC.memberId 없음
  - chain 예외 시에도 MDC.remove 보장
- `SecurityWebMvcTest` (기존 확장):
  - `@Import(JwtAuthenticationFilter.class)` → `@Import(JwtAuthenticationFilterConfig.class)`로 변경 후 기존 시나리오 회귀 없음
  - 인증 요청 Controller 호출 중 MDC.memberId 값 검증
  - 비인증 요청에 MDC.memberId 없음 검증
