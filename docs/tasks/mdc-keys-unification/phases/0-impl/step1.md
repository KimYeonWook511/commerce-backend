# Step 1: extract-mdc-keys

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 의도와 작업 범위를 파악하라.

- `docs/tasks/mdc-keys-unification/prd.md`
- `docs/tasks/mdc-keys-unification/architecture.md`
- `docs/tasks/mdc-keys-unification/adr.md`
- `docs/tasks/mdc-keys-unification/api-spec.md`
- `docs/tasks/mdc-keys-unification/db-schema.md`

수정 대상 main 소스:

- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java`
- `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`
- `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java`
- `src/main/java/com/commerce/common/log/kafka/TraceIdRecordInterceptor.java`
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`

수정 대상 test 소스:

- `src/test/java/com/commerce/common/log/filter/TraceIdFilterTest.java`
- `src/test/java/com/commerce/common/log/filter/TraceIdFilterIntegrationTest.java`
- `src/test/java/com/commerce/common/log/filter/AccessLogFilterTest.java`
- `src/test/java/com/commerce/security/filter/JwtAuthenticationFilterTest.java`
- `src/test/java/com/commerce/security/SecurityWebMvcTest.java`
- `src/test/java/com/commerce/common/log/kafka/TraceIdKafkaPropagationIntegrationTest.java`

## 작업

### 신규 파일: `MdcKeys`

`src/main/java/com/commerce/common/log/MdcKeys.java`

- `package com.commerce.common.log;`
- `public final class MdcKeys` + `private MdcKeys() {}`로 인스턴스화 차단.
- 노출 상수 4개만 둔다.
  - `public static final String TRACE_ID = "traceId";`
  - `public static final String MEMBER_ID = "memberId";`
  - `public static final String TRACE_ID_HEADER = "X-Trace-Id";`
  - `public static final java.util.regex.Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");`
- 다른 상수·메서드 추가 금지.

### main 소스 변경

다음 5개 파일에서 중복된 상수 정의를 제거하고 `MdcKeys.*` 참조로 교체한다. 흐름·예외 처리·`MDC.remove` 호출 위치·기존 주석은 변경하지 않는다.

- `TraceIdFilter`: 클래스 내부 `TRACE_ID_HEADER`, `TRACE_ID_MDC_KEY`, `VALID_TRACE_ID` 상수 제거 → `MdcKeys.TRACE_ID_HEADER`, `MdcKeys.TRACE_ID`, `MdcKeys.VALID_TRACE_ID` 참조. 기존 `// MDC.clear() 금지 ...` 주석 유지.
- `TraceIdKafkaProducerInterceptor`: 동일하게 세 상수 제거 → `MdcKeys.*` 참조.
- `TraceIdRecordInterceptor`: 동일하게 세 상수 제거 → `MdcKeys.*` 참조. `success`/`failure`/`afterRecord` 콜백 안 `MDC.put`/`MDC.remove` 호출 위치 유지. 기존 주석 유지.
- `AccessLogFilter`: `MEMBER_ID_MDC_KEY` 상수만 제거 → `MdcKeys.MEMBER_ID` 참조. `MEMBER_ID_ATTRIBUTE`는 그대로 둔다(HTTP request attribute라 MDC 키와 분리). 기존 주석(`// path 제외 목록 미적용 ...`) 유지.
- `JwtAuthenticationFilter`: `AccessLogFilter.MEMBER_ID_MDC_KEY` 참조 → `MdcKeys.MEMBER_ID`. `AccessLogFilter.MEMBER_ID_ATTRIBUTE` 참조는 그대로 둔다. 기존 주석(`// 반드시 정리`, `// 나중에 설정 파일로 분리하기` 등) 유지.

### test 소스 변경

assertion·MDC 조작에 쓰이는 정적 참조와 리터럴만 `MdcKeys.*`로 교체한다. `@DisplayName`, 주석, 로그 메시지 같은 사람이 읽는 표시 텍스트는 그대로 둔다.

- `TraceIdFilterTest`: `TraceIdFilter.TRACE_ID_HEADER`, `TraceIdFilter.TRACE_ID_MDC_KEY` 참조 → `MdcKeys.TRACE_ID_HEADER`, `MdcKeys.TRACE_ID`.
- `TraceIdFilterIntegrationTest`: 동일.
- `AccessLogFilterTest`: `AccessLogFilter.MEMBER_ID_MDC_KEY` → `MdcKeys.MEMBER_ID`.
- `JwtAuthenticationFilterTest`: `MDC.get("memberId")`, `MDC.put("memberId", ...)` 리터럴을 `MdcKeys.MEMBER_ID` 참조로 변경.
- `SecurityWebMvcTest`: `MDC.get("memberId")` 리터럴 3곳을 `MdcKeys.MEMBER_ID` 참조로 변경.
- `TraceIdKafkaPropagationIntegrationTest`: `MDC.get/put/remove("traceId")` 리터럴과 `record.headers().lastHeader("X-Trace-Id")` 리터럴을 각각 `MdcKeys.TRACE_ID`, `MdcKeys.TRACE_ID_HEADER` 참조로 변경.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria를 실행한다.
2. 다음을 확인한다.
   - `src/main/java/com/commerce/common/log/MdcKeys.java`가 신규 생성되었고 노출 상수가 4개로 한정되었는가?
   - 잔존 상수 식별자가 없는지: `grep -rn "TRACE_ID_MDC_KEY\|TRACE_ID_HEADER\|MEMBER_ID_MDC_KEY\|VALID_TRACE_ID" src/main src/test`가 `MdcKeys.java`의 정의 라인 외에는 매치 없음을 확인한다.
   - production 코드에 잔존 리터럴이 없는지: `grep -rn '"X-Trace-Id"\|"traceId"\|"memberId"' src/main`이 `MdcKeys.java`의 정의 라인 외에는 매치 없음을 확인한다(logback-spring.xml의 `%X{...}` 패턴은 예외).
   - `MEMBER_ID_ATTRIBUTE`가 `AccessLogFilter`에 그대로 유지되었는가?
   - 기존 주석(`// MDC.clear() 금지 ...`, `// path 제외 목록 미적용 ...`, `// 반드시 정리`, `// 나중에 설정 파일로 분리하기`, `TraceIdRecordInterceptor`의 `failure`/`afterRecord` 주석 등)이 보존되었는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- MDC 키 문자열 값(`"traceId"`, `"memberId"`), 응답 헤더 이름(`"X-Trace-Id"`), 정규식 값을 변경하지 마라. 이유: 동작 변화 없는 refactor 작업이며 외부 노출 계약을 깨면 옵저버빌리티와 외부 시스템에 회귀가 생긴다.
- `MEMBER_ID_ATTRIBUTE`를 `MdcKeys`로 옮기지 마라. 이유: HTTP request attribute라 MDC 키와 성격이 다르며, `AccessLogFilter` 내부에서만 쓰이는 캡슐화된 식별자다.
- 별도 상수 클래스(`TraceIdConstants`, `TraceIdHeader` 등)를 추가하지 마라. 이유: 본 태스크는 `MdcKeys` 단일 클래스 통합을 결정했다. 이중화하면 정의 위치가 다시 분산된다.
- `MDC.clear()`를 도입하지 마라. 이유: 다른 키(`orderId` 등 도메인 키)를 함께 날릴 수 있어 키 단위 `MDC.remove`로만 정리하는 기존 컨벤션을 유지한다.
- `logback-spring.xml`의 `%X{traceId:-}`, `%X{memberId:-}`를 수정하지 마라. 이유: logback 패턴 문자열은 Java 상수로 대체 불가능하다.
- `@DisplayName` 등 표시 텍스트를 `MdcKeys.*`로 바꾸지 마라. 이유: 사람이 읽는 텍스트는 리터럴이어야 의미가 보존된다.
- 기존 주석을 삭제하지 마라. 위치가 바뀌면 함께 이동시키되 내용은 보존한다.
- `Pattern.compile(MdcKeys.VALID_TRACE_ID.pattern())` 같은 재컴파일을 추가하지 마라. 이유: `MdcKeys.VALID_TRACE_ID`는 thread-safe `Pattern` 객체 자체를 노출하므로 그대로 `matcher(...)` 호출하면 된다.
