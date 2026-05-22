# 태스크 PRD

## 태스크명

- `logback-setup`

## 배경

- 이슈 #127로 `docs/logging-conventions.md`가 머지되어 로깅 정책(레벨/포맷/마스킹/MDC)이 합의된 상태다.
- 그러나 인프라 구현은 빠져 있어 정책이 실제로 적용되지 않는다.
  - `src/main/resources/logback-spring.xml` 부재 → Spring Boot 기본값 사용
  - 파일 로깅 미설정 → 운영 환경 로그가 컨테이너 재기동 시 휘발
  - `application-{local,prod,test}.yml`에 logging 섹션 산재 → 단일 진실의 원천 부재
- 이슈 #128로 정책을 실제 인프라(logback-spring.xml)에 반영한다.

## 목표

- 환경별 appender/encoder/rolling 정책을 logback-spring.xml로 일원화한다.
- 운영 환경에서 파일 JSON 로그를 영속화한다.
- 컨벤션 §5의 마스킹 정책(`password`, `token`, `accessToken`, `refreshToken`)을 콘솔과 파일 양쪽에 자동 적용한다.

## 범위

### 포함 범위
- `src/main/resources/logback-spring.xml` 신규 작성
- `build.gradle`에 `logstash-logback-encoder:7.4` 의존성 추가
- `.gitignore`에 `logs/` 추가
- `application-{local,prod,test}.yml`의 `logging:` 섹션 제거 (단일 진실의 원천 = logback-spring.xml)
- 파일 JSON 마스킹용 커스텀 `MaskingMessageJsonProvider` 클래스 추가
- 마스킹 동작 단위테스트 추가
- 루트 `docs/architecture.md` 로깅 섹션 보강

### 제외 범위
- MDC traceId Filter 구현 (별도 후속 작업, 컨벤션 §8)
- 비동기/이벤트 경계 MDC 전파 (`TaskDecorator`, Kafka header propagation)
- 이메일 부분 마스킹 유틸 (Java 코드 책임)
- Tomcat access log 별도 포맷
- 중앙 로그 수집 인프라 (ELK/Loki 등 — Epic 비목표)
- ADR 신규 작성 (logging-conventions.md가 결정 기록 역할)

## 주요 시나리오

- 개발자가 local에서 `./gradlew bootRun` 시 ANSI 색상 콘솔 로그를 본다.
- 운영 환경에서 prod 프로파일로 기동되면 콘솔(plain) + `./logs/app.log`(JSON)가 동시에 출력되고, 100MB 도달 시 rolling되어 gzip 압축된다.
- 테스트 실행 시 ROOT WARN으로 framework 노이즈가 사라지고, 비즈니스 패키지(`com.commerce.outbox` 등)만 INFO로 출력된다.
- 누군가 `log.info("...password=hunter2...")`를 실수로 호출해도 콘솔/파일 모두 `password=***`로 자동 마스킹된다.

## 요구사항

- 환경별 ROOT 레벨: local=INFO, prod=INFO, test=WARN
- 파일 출력: prod 전용, rolling 100MB/30일/3GB, gzip 압축
- 콘솔 색상: local만 ANSI 색상, prod/test는 plain
- JSON 필드: timestamp(ISO-8601 UTC), level, logger, thread, message, traceId, userId, exception, app
- SQL 로그: p6spy만 활성, `org.hibernate.SQL`은 모든 환경 OFF (중복 방지). prod에서는 p6spy도 OFF
- 외부 noisy logger 침묵: `org.springframework`, `org.hibernate`, `io.netty`, `org.apache.kafka` 등 prod에서 WARN 이상
- 마스킹 대상: `password`, `token`, `accessToken`, `refreshToken` — 콘솔은 `%replace`, 파일은 커스텀 `MaskingMessageJsonProvider`

## 제약사항

- Spring Boot 3.5.9 + Logback 1.5.x 호환 필요 (logstash-logback-encoder 7.4 선택)
- 로그 경로는 `./logs/` 고정 (env override 없음). 운영 환경에서는 호스트 볼륨 마운트 책임을 commerce-infra에 위임 (PR 본문에 명시).
- `src/test/java/com/commerce/test/async/AsyncTest.java`의 `@SpringBootTest(properties = "logging.level.p6spy=OFF")`는 단일 진실의 원천 원칙의 의도된 예외 — 테스트 케이스별 동작 보존을 위해 유지.
- 클래스패스에 `logback.xml`이 추가되면 `<springProfile>` 태그가 무력화됨 — 추후 누군가 추가하지 않도록 PR 본문에 명시.
