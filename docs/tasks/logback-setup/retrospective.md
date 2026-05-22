# logback-setup 회고

## 배경

이 작업은 이슈 #128로 진행한 로깅 인프라 구성 태스크다. 의존 이슈인 #127에서 `docs/logging-conventions.md`가 머지되어 로그 레벨, 레이어별 책임, 예외 로깅, 마스킹 정책, MDC 운영 규칙, 출력 포맷이 합의된 상태였다. 그러나 정책을 실제로 적용할 인프라(logback 설정)는 빠져 있어서 컨벤션이 코드 위에서 동작하지 않는 상황이었다.

작업 전 저장소 상태는 다음과 같았다.

- `src/main/resources/logback-spring.xml` 부재 → Spring Boot 기본 콘솔 출력만 사용
- 파일 로깅 미설정 → prod 컨테이너 재기동 시 stdout 캡처분 외 로그가 휘발
- `application-{local,prod,test}.yml`에 `logging:` 섹션이 산재 → 환경별 logger 레벨이 yml과 미구현 logback-spring.xml 양쪽에 분산될 위험
- 민감 정보 자동 마스킹 부재 → `password=`, `token=` 평문이 그대로 로그에 노출될 가능성

이 태스크는 컨벤션 §5 마스킹 정책과 §9 포맷 정책을 실제 logback 설정으로 옮기는 것을 목표로 했다.

---

## 결정사항 요약

본 태스크 내부에서 내린 인프라 차원의 결정은 `prd.md`와 `adr.md`에 기록되어 있다. 회고록에서는 결과만 인용한다.

| 항목 | 결정 |
|------|------|
| logstash-logback-encoder 버전 | 7.4 |
| 환경별 ROOT 레벨 | local=INFO, prod=INFO, test=WARN |
| 파일 출력 활성 환경 | prod 전용, rolling 100MB / 30일 / 3GB, gzip 압축 |
| 콘솔 색상 | local만 ANSI, prod/test는 plain |
| JSON 타임스탬프 | ISO-8601 UTC |
| 마스킹 대상 키워드 | `password`, `accessToken`, `refreshToken`, `token` |
| 마스킹 구현 | 콘솔은 `%replace`, 파일 JSON은 커스텀 `MaskingMessageJsonProvider` |
| SQL 로그 | p6spy만 활성(local/test), `org.hibernate.SQL`은 전 환경 OFF. prod는 p6spy도 OFF |
| 단일 진실의 원천 | `src/main/resources/logback-spring.xml`. yml의 `logging:` 섹션은 제거 |
| 로그 파일 경로 | `./logs/app.log` 고정 (env override 없음) |

ADR은 추가하지 않았다. `docs/logging-conventions.md`가 이미 "되돌리기 어려운 결정"을 다루고 있어 ADR로 중복 기록하면 단일 진실의 원천 원칙에 어긋난다는 판단이었다.

---

## 진행 중 트레이드오프

### 파일 JSON 마스킹: `<pattern>` provider 직접 조립 vs 커스텀 `MaskingMessageJsonProvider`

LogstashEncoder의 `<pattern>` provider로 message 필드를 직접 조립하면 별도 Java 클래스 없이 logback-spring.xml 안에서 마스킹을 해결할 수 있다. 그러나 message 본문에 따옴표·제어문자·중괄호가 포함된 경우 JSON escape를 직접 책임져야 한다.

`net.logstash.logback.composite.loggingevent.MessageJsonProvider`를 상속해 `event.getFormattedMessage()`에 정규식 치환만 적용하는 방식을 택했다. 약 30 LOC 클래스 한 개의 추가 비용으로 escape는 Jackson `JsonGenerator.writeStringField`가 책임진다. 마스킹 정규식만 클래스 상수와 logback-spring.xml의 `MASK_PATTERN` property 두 곳에 동일하게 두는 비용은 키워드 추가 시점에 두 곳을 함께 수정하는 규율로 해결한다.

### p6spy + `org.hibernate.SQL` 중복 처리: 둘 다 활성 vs p6spy 단일화

기존 yml에는 p6spy와 `org.hibernate.SQL`이 함께 활성화되어 있어 동일 SQL이 두 logger로 중복 출력되는 구조였다. p6spy는 파라미터 바인딩된 실 SQL을 출력하고, `org.hibernate.SQL`은 placeholder 형태의 SQL을 출력한다. 디버깅에 양쪽 정보가 모두 필요한 경우는 드물고, 파일 로그까지 활성화되는 prod에서는 동일 SQL의 중복 출력이 디스크와 노이즈에 그대로 부담이 된다.

p6spy를 단일 SQL 채널로 두고 `org.hibernate.SQL`은 전 환경 OFF로 정리했다. p6spy가 파라미터 포함 SQL을 더 직관적으로 보여주기 때문에 디버깅 가치도 단일화 쪽이 크다.

### prod p6spy 레벨: 디버깅 보존 vs 운영 침묵

p6spy를 prod에서도 켜두면 사후 SQL 추적이 가능하지만, 매 쿼리마다 로그가 한 줄씩 누적되어 디스크와 throughput에 부담을 준다. 운영 환경에서 SQL 단위 추적이 필요한 시점은 드물고, 필요한 경우 슬로우 쿼리 분석은 DB 자체 도구(`slow_query_log` 등)로 대체 가능하다.

prod에서는 p6spy 자체를 OFF로 두는 쪽을 택했다. 운영 침묵 우선이다.

### 로그 경로: env override vs 고정

`./logs/app.log` 경로를 환경변수로 override 가능하게 만들면 운영 환경에서 다른 마운트 포인트를 쓸 수 있어 유연하지만, 설정 분기점이 늘어나고 잘못된 경로로 fallback될 위험도 같이 늘어난다. 운영 환경의 호스트 경로 매핑은 컨테이너 볼륨 마운트(`-v /var/log/commerce:/app/logs`)에서 해결하는 것이 자연스러운 책임 경계다.

경로는 `./logs/app.log` 고정으로 두고, 호스트 측 매핑 책임은 commerce-infra에 위임했다. PR 본문과 prd.md에 이 위임 사실을 명시했다.

### 콘솔 색상: 모두 plain vs local만 색상

prod 환경에서 ANSI 색상 코드가 출력되면 docker stdout 캡처나 후속 로그 수집 파이프라인에서 raw escape sequence(`\x1b[31m` 등)가 노이즈로 남는다. test 환경도 CI 캡처 출력에 색상이 끼면 가독성을 떨어뜨린다.

local에서만 색상을 켜고 prod/test는 plain으로 두는 비대칭을 택했다. 로컬 가독성과 운영 클린 출력을 모두 잡기 위한 결정이다.

---

## 단일 진실의 원천 예외 1건

이번 태스크의 원칙은 "환경별 logger 레벨·appender 구성은 logback-spring.xml이 단일 진실의 원천"이다. yml의 `logging:` 섹션은 전부 제거해 두 곳에 흩어진 설정으로 인한 혼란을 차단했다.

다만 `src/test/java/com/commerce/test/async/AsyncTest.java`의 `@SpringBootTest(properties = "logging.level.p6spy=OFF")` 한 줄은 의도된 예외로 보존했다.

이 테스트는 비동기 처리 검증을 목표로 하며 p6spy 출력이 검증 로직에 끼어들면 안 된다. 클래스 단위에서 logging level을 OFF로 명시하는 것이 테스트의 의도를 코드 자체에 남기는 더 정확한 표현이다. logback-spring.xml의 test 프로파일에서 p6spy를 일괄 OFF로 두면 다른 테스트의 p6spy 로그도 함께 사라지므로, 클래스 단위 override 방식이 더 적절하다.

prd.md의 제약사항 절에도 이 예외를 명시해, 향후 누군가 "단일 진실의 원천 위반"으로 이 줄을 제거하지 않도록 의도를 박제했다.

---

## 후속 작업 제안

`docs/logging-conventions.md`의 "정하지 않는 것" 절에 명시된 항목들이 본 태스크의 자연스러운 후속 작업이다.

- **MDC traceId Filter 도입**: `OncePerRequestFilter`로 요청 진입 시 `traceId`/`userId`를 MDC에 push하고 `finally`에서 `MDC.clear()`로 정리한다. 현재 logback-spring.xml의 콘솔 패턴과 파일 JSON `<mdc/>` provider는 이미 MDC를 읽도록 구성되어 있으므로, Filter만 추가되면 즉시 출력에 반영된다.
- **비동기/이벤트 경계 MDC 전파**: `@Async`는 `TaskDecorator`로, Kafka consumer는 header에 traceId를 실어 보낸 뒤 consumer 측에서 MDC로 복원하는 방식으로, `@TransactionalEventListener(AFTER_COMMIT)`는 이벤트 publish 시점의 MDC를 복사해 전달하는 방식으로 각각 다룬다.
- **마스킹 대상 키워드 확장**: 현재 4개(`password`, `accessToken`, `refreshToken`, `token`)는 컨벤션 §5 정책 범위다. 운영 중 신규 민감 필드가 발견되면 logback-spring.xml의 `MASK_PATTERN` property와 `MaskingMessageJsonProvider.MASK_PATTERN` 두 곳을 함께 갱신한다.
- **이메일 부분 마스킹 유틸**: 컨벤션 §5는 이메일을 원칙적으로 로그에 안 남기되, 어쩔 수 없이 들어가는 경우 `a***@b.com` 부분 마스킹을 적용한다고 정했다. 정규식 기반 자동 마스킹은 false positive 위험이 커서, Java 유틸 메서드로 호출 측에서 명시적으로 마스킹하는 형태가 적절하다.
- **ERROR 무손실 보장**: 현재 `FILE_JSON_ASYNC`는 `neverBlock=true`로 throughput 우선이다. 큐 가득 시 이벤트가 유실될 수 있다. ERROR 레벨 무손실이 필요해지면 동기 appender를 ERROR 전용으로 분리하거나 큐 사이즈를 조정하는 검토가 필요하다.
- **중앙 로그 수집 인프라**: ELK/Loki 등 중앙 수집 파이프라인은 본 Epic의 비목표였다. 파일 JSON 포맷은 이미 수집 친화적으로 준비되어 있어, 인프라 도입 시점에 추가 코드 변경 없이 연동 가능하다.
- **prod 컨테이너 볼륨 마운트**: `./logs` 경로가 호스트 볼륨에 매핑되어야 컨테이너 재기동 시에도 로그가 보존된다. 매핑 책임은 commerce-infra 측 docker compose 설정에 위임했다. infra 작업으로 별도 처리된다.
- **로그 보관 기간**: 컨벤션은 "무기한 보관하지 않는다"만 정의했고 구체 일수는 정하지 않았다. 운영 로그 파이프라인 작업에서 결정한다. 현재 rolling 정책(30일/3GB)은 임시 상한이며 정식 보관 기간은 별개로 봐야 한다.
