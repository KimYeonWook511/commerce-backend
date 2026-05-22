# traceid-mdc-filter 회고

## 배경

이 작업은 이슈 #129로 진행한 요청 단위 traceId MDC 주입 태스크다. 로깅 Epic #133의 P2 작업으로, P0(로깅 컨벤션 문서, 이슈 #127)과 P1(logback 설정, 이슈 #128)이 완료된 뒤 진행했다.

P0/P1에서 `docs/logging-conventions.md §8` MDC 운영 정책과 `logback-spring.xml` 인프라가 모두 준비된 상태였다. 콘솔 패턴 `[traceId=%X{traceId:-} userId=%X{userId:-}]`과 파일 JSON `<mdc/>` provider가 MDC를 읽도록 구성되어 있었으나, MDC를 채워주는 Filter가 없어 traceId가 항상 빈 문자열로 출력되는 상태였다.

작업 전 저장소 상태는 다음과 같았다.

- `logback-spring.xml`에 `%X{traceId:-}` 패턴 존재 → 항상 빈 문자열 출력
- 동시 요청이 들어오면 어느 요청의 로그인지 추적 불가
- 장애 발생 시 요청 단위 로그 흐름을 따라갈 수 없는 상태

남은 P3/P4 작업(이슈 #130, #131)은 userId MDC push 및 Controller·Exception·외부 호출 로깅 표준화다. 이 태스크에서 traceId 인프라가 완성되어야 P3/P4의 MDC 확장이 안정적으로 쌓일 수 있다.

---

## 결정사항 요약

본 태스크 내부에서 내린 구현 차원의 결정은 `prd.md`와 `architecture.md`에 기록되어 있다. 회고록에서는 결과만 인용한다.

| 항목 | 결정 | 근거 |
|------|------|------|
| traceId 생성 방식 | UUID v4 36자 (java.util.UUID) | 외부 의존성 0. 분산 추적 표준(128-bit)과 호환 |
| Filter 패키지 | `com.commerce.common.log.filter` | MaskingMessageJsonProvider와 같은 곳에 로깅 인프라 응집 |
| Filter 등록 방식 | FilterRegistrationBean (Config Bean) | @Component 중복 등록 방지 + order 명시 |
| Filter order | `Ordered.HIGHEST_PRECEDENCE + 10` | JwtAuthenticationFilter(LOWEST_PRECEDENCE) 전 실행 보장 |
| incoming 헤더 검증 | `^[A-Za-z0-9_-]{1,64}$` | 로그 인젝션 차단 + UUID/일반 alphanum 허용 |
| MDC.remove vs MDC.clear | `MDC.remove("traceId")` 사용 | 향후 P3/P4에서 추가될 다른 MDC 키를 같이 날리는 위험 차단 |
| CORS expose 헤더 | 추가 없음 | 프로젝트 CORS 설정 미사용(확인됨) |

ADR은 추가하지 않았다. `docs/logging-conventions.md §8`이 이미 핵심 결정(traceId MDC 운영 정책, 키명 `traceId`, Filter 책임, MDC.clear 금지)을 다루고 있어 ADR로 중복 기록하면 단일 진실의 원천 원칙에 어긋난다는 판단이었다.

---

## 진행 중 트레이드오프

### UUID v4 36자 vs nanoid 21자

nanoid는 21자로 UUID(36자)보다 짧고 URL-safe 문자셋을 기본으로 쓴다. 그러나 별도 의존성 추가(또는 자체 구현)가 필요하다. `java.util.UUID`는 JDK 표준 라이브러리로 추가 의존성이 0이며, 분산 추적 표준(W3C Trace Context, OpenTelemetry)의 128-bit 식별자와 구조적으로 호환된다. 운영 환경에서 traceId가 짧은 것보다 외부 의존성이 없고 표준 호환성이 높은 것이 더 중요하다고 판단해 UUID v4를 선택했다.

### TraceIdFilter 위치: `common/log/filter` vs `security/filter`

Filter는 `jakarta.servlet.Filter` 구현체이므로 security 패키지에 두는 것도 자연스럽다. 그러나 traceId는 인증·인가와 무관한 횡단 관심사(logging infrastructure)다. 같은 패키지에 `MaskingMessageJsonProvider`가 이미 있어 `com.commerce.common.log.filter`가 로깅 인프라를 응집하는 패키지로 자리잡고 있었다. 보안 필터(JwtAuthenticationFilter)와 로깅 필터를 분리하면 역할 경계가 명확해지고, 향후 userId MDC push가 인증 흐름과 연결될 때 의존 방향이 `security → log`가 아닌 `log → (별도 컨텍스트)`로 유지된다.

### `MDC.remove` vs `MDC.clear`

`MDC.clear()`를 사용하면 TraceIdFilter의 `finally` 블록 한 줄로 모든 MDC를 정리할 수 있다. 그러나 향후 P3/P4에서 인터셉터·AOP가 `userId`, `orderId` 등 다른 키를 push할 경우, TraceIdFilter의 `finally`에서 `MDC.clear()`를 호출하면 그 키들까지 모두 날리게 된다. 자신이 push한 키만 `MDC.remove("traceId")`로 제거해 다른 MDC 키의 생명주기를 간섭하지 않도록 했다. 이 결정은 `docs/logging-conventions.md §8`의 명시적 정책을 그대로 따른 것이다.

### `@Component` vs `FilterRegistrationBean` 단독 등록

`@Component`를 붙이면 Spring Boot가 Filter를 자동 등록한다. 그러나 order를 제어하려면 어차피 `FilterRegistrationBean`이 필요하며, `@Component`와 `FilterRegistrationBean`을 병용하면 Filter가 두 번 등록되는 문제가 발생한다. `FilterRegistrationBean` 단독 등록으로 이중 등록 위험을 차단하고 order를 명시했다.

### Filter order: `HIGHEST_PRECEDENCE` vs `HIGHEST_PRECEDENCE + 10`

`Ordered.HIGHEST_PRECEDENCE`를 그대로 사용하면 TraceIdFilter보다 먼저 실행되어야 할 Filter(예: 요청 파싱, 에러 처리)를 나중에 끼워 넣기 어렵다. `+ 10` 여유를 두어 향후 더 높은 우선순위가 필요한 Filter를 `HIGHEST_PRECEDENCE`에서 `HIGHEST_PRECEDENCE + 9` 사이에 배치할 수 있는 공간을 확보했다. JwtAuthenticationFilter는 `Ordered.LOWEST_PRECEDENCE`(Spring Boot 기본)이므로 TraceIdFilter가 반드시 먼저 실행된다.

### incoming traceId 검증 여부: 검증 없음 vs 패턴 검증

incoming `X-Trace-Id`를 그대로 MDC에 push하면 로그 인젝션 공격에 취약해진다. `<script>alert(1)</script>` 같은 값이 로그에 그대로 기록되면 로그 수집 시스템에서 파싱 오류가 발생하거나 보안 이슈로 이어질 수 있다. `^[A-Za-z0-9_-]{1,64}$` 패턴으로 UUID 형식과 일반 alphanum을 허용하면서 특수문자를 차단했다. 패턴 불통과 시 새 UUID를 발급해 클라이언트 측 혼란도 방지했다.

---

## 후속 작업 제안

- **P3 #130 핵심 도메인 로깅 보강**: userId MDC push를 인증 흐름과 연결해 도입한다. TraceIdFilter에서 traceId를 먼저 push한 뒤 JwtAuthenticationFilter에서 인증 성공 시 userId를 push하는 흐름이 된다. orderId, paymentId 등 도메인 식별자 MDC 확장도 이 단계에서 다룬다.
- **P4 #131 Controller·Exception·외부 호출 로깅 표준화**: Controller 진입/반환 로그, GlobalExceptionHandler 예외 로그, 외부 HTTP 호출 로그의 형식과 수준을 통일한다.
- **`@Async`, Kafka consumer, `@TransactionalEventListener` traceId 전파**: 비동기 경계에서 MDC가 초기화되어 traceId가 유실된다. `@Async`는 `TaskDecorator`로, Kafka consumer는 헤더에 traceId를 실어 consumer 측에서 MDC로 복원하는 방식으로, `@TransactionalEventListener`는 이벤트 publish 시점의 MDC를 복사해 전달하는 방식으로 각각 다룬다.
- **외부 HTTP 호출 시 X-Trace-Id out-bound 전파**: RestTemplate, WebClient, FeignClient 등에서 외부 시스템 호출 시 현재 MDC의 traceId를 `X-Trace-Id` 헤더로 전달한다. 향후 게이트웨이·MSA 환경에서의 분산 추적 기반이 된다.
- **MdcKeys 상수 클래스 추출**: 현재 `"traceId"` 문자열이 Filter와 테스트에 직접 쓰인다. P3에서 `userId`, `orderId` 등 키가 늘어나는 시점에 `MdcKeys` 상수 클래스를 추출해 키 불일치 위험을 제거한다.
