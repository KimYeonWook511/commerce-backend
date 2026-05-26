# 태스크 PRD

## 태스크명

- `mdc-keys-unification`

## 배경

- 이슈 #150 후속 리팩토링 작업이다.
- Kafka traceId 전파(#149 / kafka-trace-propagation) 구현 결과, `TRACE_ID_HEADER("X-Trace-Id")`, `TRACE_ID_MDC_KEY("traceId")`, 유효성 정규식(`^[A-Za-z0-9_-]{1,64}$`)이 다음 3개 클래스에 동일한 형태로 중복 정의되어 있다.
  - `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java`
  - `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java`
  - `src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java`
- 추가로 `MEMBER_ID_MDC_KEY("memberId")`도 `AccessLogFilter`에 정의되어 `JwtAuthenticationFilter`가 외부에서 참조하는 형태로 두 클래스에 흩어져 있다.
- Kafka 태스크 범위에서 추출하면 HTTP Filter까지 함께 손대야 해 의도적으로 미뤘다. 본 태스크는 `kafka-trace-propagation` 회고록과 `traceid-mdc-filter` PRD에서 후속 과제로 명시된 작업을 정리하는 PR이다.
- PR 코드리뷰에서 클래스 이름(`MdcKeys`)과 내용 불일치, MDC 조작 분산 문제가 지적되어 `LogContext`로 대체하고 MDC 조작을 메서드로 캡슐화하는 방향으로 확장되었다.

## 목표

- MDC 키·관련 상수(MDC 키, traceId 헤더 이름, traceId 유효성 정규식)를 단일 `LogContext` 클래스로 통합해 정의 위치를 일원화한다.
- MDC 조작(`put`/`get`/`remove`)과 유효성 검증을 메서드로 캡슐화해 호출부가 키 문자열을 직접 다루지 않도록 한다.
- 모든 사용처가 `LogContext`를 단일 출처로 참조하게 만든다.
- 동작과 외부 계약은 변경하지 않는다. 본 태스크는 식별자·기호 정리와 캡슐화만 수행한다.

## 범위

### 포함 범위

- 신규 `src/main/java/com/commerce/common/log/LogContext.java` (구 `MdcKeys.java` 삭제)
- main 소스 5개 갱신:
  - `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java`
  - `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java`
  - `src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java`
  - `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java` — `MEMBER_ID_MDC_KEY`만 제거. `MEMBER_ID_ATTRIBUTE`는 HTTP request attribute라 그대로 유지.
  - `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java` — `LogContext.putMemberId()`, `LogContext.removeMemberId()` 사용.
- test 소스 6개 갱신 (assertion·MDC 조작에 쓰이는 리터럴/정적 참조만 교체. `@DisplayName` 등 표시 텍스트는 유지):
  - `src/test/java/com/commerce/common/log/filter/TraceIdFilterTest.java`
  - `src/test/java/com/commerce/common/log/filter/TraceIdFilterIntegrationTest.java`
  - `src/test/java/com/commerce/common/log/filter/AccessLogFilterTest.java`
  - `src/test/java/com/commerce/security/filter/JwtAuthenticationFilterTest.java`
  - `src/test/java/com/commerce/security/SecurityWebMvcTest.java`
  - `src/test/java/com/commerce/common/log/kafka/TraceIdKafkaPropagationIntegrationTest.java`
- 루트 문서 갱신: `docs/logging-conventions.md` §8에 "MDC 키는 `LogContext`에서 단일 관리한다" 명시 추가.

### 제외 범위

- `src/main/resources/logback-spring.xml`의 `%X{traceId:-}`, `%X{memberId:-}` — logback 패턴 문자열은 Java 상수로 대체 불가능하므로 그대로 둔다.
- 테스트 `@DisplayName("... X-Trace-Id ...")` 같은 사람이 읽는 표시 텍스트.
- 과거 task 문서(`docs/tasks/traceid-mdc-filter/...`, `docs/tasks/memberid-mdc-propagation/retrospective.md`, `docs/tasks/core-domain-logging/retrospective.md`, `docs/tasks/kafka-trace-propagation/...`)에 남은 "MdcKeys 통합 리팩토링 PR에서 일괄 처리" 후속 작업 언급 — 역사 기록 원칙에 따라 사후 수정하지 않는다.
- `MEMBER_ID_ATTRIBUTE`(HTTP request attribute) — MDC 키가 아니므로 `LogContext`로 이동하지 않는다.
- `orderId`, `paymentId` 등 도메인 식별자 MDC 키 — 본 태스크에서 신규 도입하지 않는다. 도메인 도입 시점에 `LogContext`에 추가한다.

## 주요 시나리오

- HTTP 요청이 들어오면 `TraceIdFilter`가 `LogContext.TRACE_ID_HEADER`로 요청 헤더를 읽고 `LogContext.putTraceId()`로 MDC에 push한 뒤 동일 헤더로 응답에 set한다. 동작과 응답 헤더 이름은 변경 전과 동일하다.
- 인증된 요청은 `JwtAuthenticationFilter`가 `LogContext.putMemberId()`로 MDC에 push한다. 동작과 MDC 키 문자열은 변경 전과 동일하다.
- Kafka producer는 메시지 발행 시 `LogContext.TRACE_ID_HEADER` 헤더에 `LogContext.getTraceId()` 값(또는 신규 UUID)을 부착한다. 동작은 변경 전과 동일하다.
- Kafka consumer는 동일 헤더에서 traceId를 추출해 `LogContext.putTraceId()`로 MDC에 push하고, `afterRecord()`에서 `LogContext.removeTraceId()`로 정리한다. 동작은 변경 전과 동일하다.

## 요구사항

- 신규 `LogContext` 클래스를 `com.commerce.common.log` 패키지에 둔다.
- `LogContext`는 `public final class` + `private` 기본 생성자로 인스턴스화를 차단한다.
- 공개 API는 다음으로 한정한다.
  - `public static final String TRACE_ID_HEADER = "X-Trace-Id"` — HTTP·Kafka 프레임워크 API에 직접 전달해야 하므로 public 상수로 유지
  - `public static void putTraceId(String traceId)`
  - `public static String getTraceId()`
  - `public static void removeTraceId()`
  - `public static boolean isValidTraceId(String traceId)`
  - `public static void putMemberId(long memberId)`
  - `public static String getMemberId()`
  - `public static void removeMemberId()`
- `TRACE_ID`, `MEMBER_ID`, `VALID_TRACE_ID`는 `private`으로 내려 외부에 노출하지 않는다.
- 사용처는 모두 `LogContext`를 import해 참조한다. 클래스 내부 `private/static final` 중복 상수와 직접 리터럴은 제거한다(예외: logback 패턴, `@DisplayName` 표시 텍스트).
- 외부에 노출되는 MDC 키 문자열, HTTP 응답 헤더 이름, 정규식 패턴 값은 변경하지 않는다.
- `MEMBER_ID_ATTRIBUTE`는 `AccessLogFilter`에 유지한다.

## 제약사항

- 동작 변화 없는 refactor 작업이다. Filter/Interceptor의 흐름·예외 처리·remove 호출 위치는 그대로 유지한다.
- `MDC.clear()` 도입 금지 — 기존 컨벤션대로 키 단위 `LogContext.removeTraceId()` / `LogContext.removeMemberId()`만 사용한다.
- `LogContext` 외 별도 상수 클래스 신설을 금한다.
