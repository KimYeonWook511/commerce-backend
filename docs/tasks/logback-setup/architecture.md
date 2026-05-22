# 태스크 아키텍처

## 개요

이번 태스크는 application 코드 레이어에는 영향을 주지 않는 인프라(설정) 작업이다. `src/main/resources/logback-spring.xml`을 신규 작성해 환경별 로그 출력 채널·포맷·rolling·마스킹을 일원화한다. 도메인/서비스 계층의 `@Slf4j` 사용 방식은 그대로 유지된다.

## 변경 대상

- **Build**: `build.gradle` — `logstash-logback-encoder:7.4` 의존성 추가
- **Resources**: 
  - `src/main/resources/logback-spring.xml` (신규)
  - `src/main/resources/application-{local,prod,test}.yml` (logging 섹션 제거)
- **Global infrastructure code**: `src/main/java/com/commerce/global/logging/MaskingMessageJsonProvider.java` (신규)
- **Test infrastructure**: `src/test/java/com/commerce/global/logging/LoggingMaskingTest.java` (신규)
- **Repo meta**: `.gitignore` (`logs/` 추가)
- **Docs**: `docs/architecture.md` (로깅 인프라 한 줄 보강)

## 설계 방향

### 단일 진실의 원천
- 환경별 logger 레벨·appender 구성은 모두 `logback-spring.xml`에 둔다.
- yml의 `logging:` 섹션은 제거해 두 곳에 흩어진 설정으로 인한 혼란을 차단한다.

### 환경별 appender 분기
- Spring Boot의 `<springProfile name="...">` 태그로 local/prod/test 블록을 분리한다.
- 각 환경은 ROOT logger에 필요한 appender만 결합한다.
  - local: `CONSOLE_COLOR`
  - prod: `CONSOLE_PLAIN` + `FILE_JSON_ASYNC`
  - test: `CONSOLE_PLAIN`

### 마스킹 이중화
- 콘솔: `PatternLayoutEncoder`의 `%replace(...)` 토큰으로 정규식 치환.
- 파일 JSON: `LoggingEventCompositeJsonEncoder`는 message 필드의 자체 정규식 치환을 지원하지 않으므로, `net.logstash.logback.composite.loggingevent.MessageJsonProvider`를 상속한 커스텀 `MaskingMessageJsonProvider`로 `event.getFormattedMessage()`에 치환을 적용한다.
- 마스킹 패턴은 logback-spring.xml의 `<property name="MASK_PATTERN">` 한 곳에서 관리(콘솔용)하고, Java 클래스에서도 동일한 정규식을 상수로 둔다. 키워드 추가는 두 곳 동시 수정.

### SQL 로그 단일화
- p6spy가 SQL을 파라미터와 함께 출력하므로, `org.hibernate.SQL`은 모든 환경에서 OFF로 두어 중복을 차단한다.
- prod에서는 p6spy도 OFF (성능·디스크·노이즈 회피).

### 파일 출력 비동기화
- `RollingFileAppender`를 `AsyncAppender`로 감싸 파일 I/O 블로킹을 회피한다.
- `neverBlock=true`로 큐 가득 시 유실 허용 (throughput 우선). ERROR 무손실이 필요해지면 향후 동기 appender 분리 검토.

## 데이터 흐름

```
SLF4J log call
  ↓
Logback Logger (level filter)
  ↓
Root logger appenders (환경별)
  ├─ CONSOLE_COLOR (local)   → PatternLayoutEncoder + %replace 마스킹 → stdout (ANSI)
  ├─ CONSOLE_PLAIN (prod)    → PatternLayoutEncoder + %replace 마스킹 → stdout (plain)
  ├─ CONSOLE_PLAIN (test)    → PatternLayoutEncoder + %replace 마스킹 → stdout (plain)
  └─ FILE_JSON_ASYNC (prod)
        ↓
     AsyncAppender (queue 1024, neverBlock)
        ↓
     RollingFileAppender (100MB/30일/3GB, gzip)
        ↓
     LoggingEventCompositeJsonEncoder
        ↓
     providers: timestamp, level, logger, thread,
                MaskingMessageJsonProvider, stackTrace, mdc, app pattern
        ↓
     ./logs/app.log + ./logs/archive/app-YYYY-MM-DD.N.log.gz
```

## 예외 및 실패 처리

- **파일 시스템 쓰기 실패**: logback이 자체 ErrorHandler로 stderr에 한 번 보고 후 해당 appender 비활성화. application 로직 영향 없음.
- **마스킹 정규식 매칭 실패**: `String.replaceAll`이 매치 없으면 원본 그대로 출력. silent fail.
- **AsyncAppender 큐 오버플로**: `neverBlock=true` + `discardingThreshold=0`이므로 신규 이벤트 유실. 운영 모니터링에서 throughput 급증 시 큐 사이즈 조정 필요.
- **prod 컨테이너 `./logs` 미마운트**: 컨테이너 재기동 시 로그 유실. infra 측 조치 (commerce-infra)로 위임, PR 본문에 명시.

## 테스트 포인트

- `MaskingMessageJsonProvider`가 `password=hunter2 token=abc` 같은 메시지를 `password=*** token=***`으로 치환하는가
- 마스킹 대상이 없는 메시지는 원본을 그대로 직렬화하는가
- `PatternLayoutEncoder` + `%replace` 결합이 콘솔 출력에 동일한 마스킹을 적용하는가
- 전체 `./gradlew test` 회귀 (특히 `AsyncTest`의 인라인 `logging.level.p6spy=OFF` 동작 유지)
- `grep -rn "^logging:" src/main/resources` 결과 없음 (yml 단일 진실의 원천 회귀)
- `./gradlew dependencies` 결과에 `logstash-logback-encoder:7.4` 포함
