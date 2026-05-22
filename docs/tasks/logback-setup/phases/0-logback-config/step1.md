# Step 1: logback-setup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 로깅 정책과 설계 의도를 파악하라:

- `/docs/tasks/logback-setup/prd.md`
- `/docs/tasks/logback-setup/architecture.md`
- `/docs/tasks/logback-setup/adr.md`
- `/docs/tasks/logback-setup/api-spec.md`
- `/docs/tasks/logback-setup/db-schema.md`
- `/docs/logging-conventions.md` (정책 출처)
- `/build.gradle` (현재 의존성과 주석 카테고리 패턴 확인)
- `/.gitignore`
- `/src/main/resources/application.yml`
- `/src/main/resources/application-local.yml`
- `/src/main/resources/application-prod.yml`
- `/src/main/resources/application-test.yml`
- `/src/test/java/com/commerce/test/async/AsyncTest.java` (단일 진실의 원천 예외 케이스 확인)

## 작업

logback-spring.xml 인프라를 한 덩어리로 구축한다. 빌드 의존성, 무시 패턴, logback xml, 커스텀 provider, yml 정리, 단위테스트까지 한 step에 포함한다.

### 1-1. `build.gradle` — 의존성 추가

기존 `// 쿼리 파라미터 로그 남기기 ...` 단일 주석을 `// logging` 카테고리 헤더 + 하위 항목 주석 형태로 재구성하고 `logstash-logback-encoder`를 함께 묶는다. (로깅 관련 의존성을 한 카테고리에 모음)

```diff
-    // 쿼리 파라미터 로그 남기기 (외부 라이브러리: https://github.com/gavlyukovskiy/spring-boot-data-source-decorator)
+    // logging
+    // - SQL 쿼리 파라미터 로그 (외부 라이브러리: https://github.com/gavlyukovskiy/spring-boot-data-source-decorator)
     implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.10.0'
+    // - JSON 구조화 로그 (logback-spring.xml의 LoggingEventCompositeJsonEncoder가 의존)
+    implementation 'net.logstash.logback:logstash-logback-encoder:7.4'

     // lombok
```

### 1-2. `.gitignore` — `logs/`, `*.log.gz` 추가

```diff
 ### 로그 파일 ###
 *.log
+*.log.gz
+logs/
```

- `logs/`: logback의 `LOG_PATH=./logs` 디렉토리 통째 차단(현재 archive gz 경로 커버)
- `*.log.gz`: 누군가 미래에 archive를 `./logs/` 밖에 생성해도 차단되도록 방어 두껍게

### 1-3. `MaskingMessageJsonProvider` 신규 작성

위치: `src/main/java/com/commerce/global/logging/MaskingMessageJsonProvider.java`

요구사항:
- `net.logstash.logback.composite.loggingevent.MessageJsonProvider` 상속
- `writeTo(JsonGenerator generator, ILoggingEvent event)` 오버라이드
- 정규식 상수: `(?i)(password|accessToken|refreshToken|token)(["'\s]*[:=]["'\s]*)([^"'\s,}]+)`
- 매치된 값을 `$1$2***`로 치환 후 `generator.writeStringField("message", masked)` 호출
- 매치 없는 경우 원본 그대로 출력
- 단위테스트로 동작 검증 가능한 형태로 작성 (정적 helper 메서드 또는 인스턴스 메서드 호출 가능)

### 1-4. `src/main/resources/logback-spring.xml` 신규 작성

전체 골격은 본 태스크의 `architecture.md` "데이터 흐름" 절과 plan 파일을 기준으로 한다. 핵심:

- 공통 변수: `LOG_PATH=./logs`, `LOG_FILE=./logs/app.log`, `LOG_ARCHIVE=./logs/archive/app-%d{yyyy-MM-dd}.%i.log.gz`
- 콘솔 패턴 (plain): `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}] [traceId=%X{traceId:-} userId=%X{userId:-}] - %msg%n`
- 콘솔 패턴 (color): plain 패턴에 `%cyan`, `%highlight`, `%magenta`, `%yellow` 색상 토큰 적용
- 마스킹 정규식 property: `(?i)(password|accessToken|refreshToken|token)([&quot;'\s]*[:=][&quot;'\s]*)([^&quot;'\s,}]+)` (`&quot;`로 XML escape)
- Appender:
  - `CONSOLE_COLOR`: `ConsoleAppender` + `PatternLayoutEncoder` + `%replace` 마스킹 + `<withJansi>true</withJansi>`
  - `CONSOLE_PLAIN`: `ConsoleAppender` + `PatternLayoutEncoder` + `%replace` 마스킹
  - `FILE_JSON`: `RollingFileAppender` + `SizeAndTimeBasedRollingPolicy` (100MB/30일/3GB/cleanHistoryOnStart) + `LoggingEventCompositeJsonEncoder`
  - `FILE_JSON_ASYNC`: `AsyncAppender` (queueSize=1024, discardingThreshold=0, neverBlock=true, includeCallerData=false) → `FILE_JSON`
- `LoggingEventCompositeJsonEncoder` providers:
  - `timestamp` (fieldName=timestamp, timeZone=UTC, pattern=`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`)
  - `logLevel` (fieldName=level)
  - `loggerName` (fieldName=logger, shortenedLoggerNameLength=40)
  - `threadName` (fieldName=thread)
  - `<provider class="com.commerce.global.logging.MaskingMessageJsonProvider"/>` (기본 `<message>` 대신)
  - `stackTrace` (fieldName=exception, `ShortenedThrowableConverter` maxDepth=50, maxLength=8192, rootCauseFirst=true)
  - `mdc/` (전체 MDC 직렬화)
  - `<pattern><pattern>{"app":"commerce-backend"}</pattern></pattern>` (앱 식별 필드)
- `<springProfile name="local,default">`:
  - `org.springframework=INFO`, `org.hibernate=INFO`, `org.hibernate.SQL=OFF`, `io.netty=INFO`, `org.apache.kafka=WARN`
  - `p6spy=INFO additivity=false → CONSOLE_COLOR`
  - `<root level="INFO"><appender-ref ref="CONSOLE_COLOR"/></root>`
- `<springProfile name="prod">`:
  - `org.springframework=WARN`, `org.hibernate=WARN`, `org.hibernate.SQL=OFF`, `io.netty=WARN`, `org.apache.kafka=WARN`, `org.apache.tomcat=WARN`, `reactor.netty=WARN`
  - `p6spy=OFF`
  - `<root level="INFO"><appender-ref ref="CONSOLE_PLAIN"/><appender-ref ref="FILE_JSON_ASYNC"/></root>`
- `<springProfile name="test">`:
  - `org.springframework=WARN`, `org.springframework.batch=WARN`, `org.springframework.boot=WARN`, `org.hibernate=WARN`, `org.hibernate.SQL=OFF`, `io.netty=WARN`, `org.apache.kafka=WARN`, `org.testcontainers=WARN`, `com.zaxxer.hikari=WARN`, `org.h2=WARN`
  - `com.commerce.order.batch.listener=INFO`, `com.commerce.outbox=INFO` (기존 yml에서 보존)
  - `p6spy=INFO additivity=false → CONSOLE_PLAIN`
  - `<root level="WARN"><appender-ref ref="CONSOLE_PLAIN"/></root>`
- `<configuration scan="false">`로 폴링 reload 비활성화

### 1-5. application yml 정리

- `application-local.yml` L34-36 `logging:` 블록 제거
- `application-prod.yml` L35-37 `logging:` 블록 제거
- `application-test.yml` L29-36 `logging:` 블록 제거

(test의 `org.hibernate.SQL: debug`/`p6spy: info`/패키지 INFO 설정은 위 logback-spring.xml `<springProfile name="test">`에서 동등 또는 더 보수적으로 보존됨.)

`src/test/java/com/commerce/test/async/AsyncTest.java:31`의 `@SpringBootTest(properties = "logging.level.p6spy=OFF")`는 인라인 prop override로 유지. 단일 진실의 원천 원칙의 의도된 예외.

### 1-6. 단위테스트 신규 작성

위치: `src/test/java/com/commerce/global/logging/LoggingMaskingTest.java`

요구사항:
- `MaskingMessageJsonProvider`를 직접 인스턴스화 → mocked `JsonGenerator` 또는 `StringWriter` 기반 실제 `JsonGenerator`로 직렬화 → ObjectMapper로 파싱해 `message` 필드 마스킹 확인
- 입력 케이스:
  - `"login attempt password=hunter2 token=abc123"` → `password=***`, `token=***` 치환 확인
  - `"json body: {\"accessToken\":\"xyz\", \"refreshToken\":\"qqq\"}"` → 두 토큰 모두 마스킹
  - `"order created orderId=42 userId=7"` → 변형 없이 원본 그대로 직렬화 (마스킹 대상 키워드 없음)
- (선택) `PatternLayoutEncoder`에 `%replace` 패턴을 직접 구성해 콘솔 마스킹 동등성을 같은 테스트 클래스에서 함께 검증해도 좋다.

## 수정 가능 경로

- `build.gradle`
- `.gitignore`
- `src/main/resources/logback-spring.xml` (신규)
- `src/main/resources/application-local.yml`
- `src/main/resources/application-prod.yml`
- `src/main/resources/application-test.yml`
- `src/main/java/com/commerce/global/logging/MaskingMessageJsonProvider.java` (신규)
- `src/test/java/com/commerce/global/logging/LoggingMaskingTest.java` (신규)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 추가 회귀 확인:
   - `grep -rn "^logging:" src/main/resources` 결과 없음
   - `./gradlew dependencies --configuration runtimeClasspath | grep logstash` 결과에 `logstash-logback-encoder:7.4` 표시
   - `src/test/java/com/commerce/test/async/AsyncTest.java`의 인라인 `logging.level.p6spy=OFF`가 여전히 동작 (테스트 통과)
3. 아래를 확인한다.
   - logback-spring.xml의 `<springProfile>` 분기가 local/prod/test 모두 명시되어 있는가?
   - 마스킹 정규식이 콘솔과 파일 양쪽에 동등하게 적용되는가?
   - p6spy logger가 `additivity="false"`로 ROOT 중복 출력을 막고 있는가?

## 금지사항

- yml에 `logging:` 섹션을 새로 추가하지 마라. 이유: 단일 진실의 원천 원칙 위반. `AsyncTest`의 인라인 prop override 1건만 의도된 예외.
- `logback.xml`(non-spring) 파일을 추가하지 마라. 이유: Spring Boot가 `<springProfile>` 태그가 무력화된 채로 우선 로딩한다.
- p6spy logger에 `additivity="false"`를 빼지 마라. 이유: ROOT logger와 중복되어 동일 SQL 라인이 두 번 출력된다.
- 마스킹 정규식의 키워드 4개(`password`, `accessToken`, `refreshToken`, `token`)를 임의로 늘리거나 줄이지 마라. 이유: `docs/logging-conventions.md` §5의 정책 범위.
- `org.hibernate.SQL`을 어떤 환경에서도 DEBUG로 켜지 마라. 이유: p6spy와 중복 출력. 본 태스크가 p6spy 단일화를 결정함.
- 기존 테스트를 깨뜨리지 마라.
