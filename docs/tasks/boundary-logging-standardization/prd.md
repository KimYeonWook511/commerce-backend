# 태스크 PRD

## 태스크명

- `boundary-logging-standardization`

## 배경

- 이슈 #127(로깅 규칙 문서), #128(logback 설정), #129(traceId MDC Filter)가 머지되어 로깅 정책과 인프라가 준비된 상태다.
- 그러나 시스템 외부 경계 3곳이 컨벤션(`docs/logging-conventions.md`)을 따르지 않는다.
  - **Controller 7개 모두 로그 없음** → HTTP 요청 단위 운영 로그 부재. 어떤 API가 호출되었는지 추적 불가.
  - **`GlobalExceptionHandler` 9개 핸들러가 4xx/5xx 무차별 `log.error`** → prod 노이즈 큼, 5xx 시스템 장애와 일상 4xx 구분 불가. `handleException`은 stack trace 누락 버그까지 있음.
  - **`NaverPayGatewayImpl` 영문 메시지 7건 + 라운드트립 패턴 비일관** → 컨벤션 §7(한국어) 위반, 외부 호출 추적 형식 제각각.
- 이슈 #131로 P4 작업을 진행해 위 3곳을 컨벤션에 맞춘다. Epic #133의 P3(#130)와 병렬 가능하다.

## 목표

- 모든 HTTP 요청에 시작/종료 액세스 로그 2건을 자동 출력해 요청 단위 운영 추적을 가능하게 한다.
- `GlobalExceptionHandler`의 로그 레벨/stack trace 정책을 컨벤션 §4와 일치시켜 prod 노이즈를 줄인다.
- `NaverPayGatewayImpl`의 외부 호출 로그를 한국어 + 표준 라운드트립 패턴으로 통일해 PG 연동 디버깅 형식을 일관화한다.

## 범위

### 포함 범위

- `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java` 신규
- `src/main/java/com/commerce/common/log/filter/AccessLogFilterConfig.java` 신규 (FilterRegistrationBean 등록)
- `src/test/java/com/commerce/common/log/filter/AccessLogFilterTest.java` 단위 테스트
- `src/test/java/com/commerce/common/log/filter/AccessLogFilterIntegrationTest.java` 통합 테스트
- `src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java` 9개 핸들러 레벨/스택 정비
- `src/test/java/com/commerce/common/exception/GlobalExceptionHandlerTest.java` 핸들러별 로그 검증 테스트 (없으면 신규)
- `src/main/java/com/commerce/payment/naverpay/infrastructure/NaverPayGatewayImpl.java` 메시지 한국어화 + 표준 라운드트립
- 루트 `docs/architecture.md` HTTP 요청 처리 Filter 절 갱신

### 제외 범위

- **memberId MDC push** — 인증 흐름과 결합되므로 P3(#130) 또는 인증 Filter 확장 후속 작업의 책임. AccessLogFilter는 MDC를 읽기만 한다.
- **요청/응답 body 로깅** — 컨벤션 §3의 "DEBUG 옵션화"는 ContentCachingWrapper 메모리 부담이 있어 본 작업에서 미지원. 디버깅 사례 발생 시 후속.
- **액세스 로그 path 제외 목록** — actuator 미사용 + favicon은 `api.*` 서브도메인이라 거의 안 옴. YAGNI로 제외 목록을 두지 않는다. noisy해지면 후속.
- **WARN 대상 4xx 분류** — 컨벤션 §4는 "잠정 목록"으로 인증·결제 검증 실패를 예시. 운영 데이터 누적 후 별도 작업에서 메타데이터화한다.
- **NaverPay 응답 body DEBUG 로깅** — 본 작업은 필드 단위(paymentId, code, message) 로깅만.
- **거래 종료 ERROR** — Gateway는 호출 단계 실패만 WARN으로 한정. 거래 종료 ERROR 판단은 호출자(`PaymentService`) 책임. 호출자 변경은 본 작업 범위 밖.
- **`@Async`·Kafka consumer의 traceId 전파** — Epic 후속 작업.

## 주요 시나리오

- 클라이언트가 `GET /products/1`을 호출하면 콘솔에 `요청 시작 method=GET path=/products/1` + `요청 종료 status=200 latency=XXms` 2줄이 출력되고, 두 줄 모두 동일 traceId가 포함된다.
- 클라이언트가 존재하지 않는 자원을 조회해 4xx CustomException이 발생하면 액세스 로그 2줄 외에는 핸들러 로그가 남지 않는다.
- DB 무결성 위반이나 NPE 등 5xx 시스템 예외가 발생하면 `GlobalExceptionHandler`가 ERROR 로그 + stack trace를 남긴다.
- `OptimisticLockingFailureException`(409)이 발생하면 WARN 로그가 남는다(stack trace 없음).
- NaverPay 승인 호출 시 `네이버페이 승인 요청 paymentId=...` INFO → 정상 시 `네이버페이 승인 응답 paymentId=... code=...` INFO, 실패 시 `네이버페이 승인 실패 paymentId=... code=... message=...` WARN, 호출 자체 실패 시 `네이버페이 승인 호출 실패 paymentId=... message=...` WARN이 남는다.

## 요구사항

- AccessLogFilter는 `OncePerRequestFilter` 상속, 모든 URL(`/*`) 적용, order = `Ordered.HIGHEST_PRECEDENCE + 20` (TraceIdFilter 다음).
- 액세스 로그 메시지는 한국어 + SLF4J placeholder `{}` 사용. traceId/memberId는 MDC를 통해 logback 패턴이 자동 부착하므로 메시지 인자로 반복 부착하지 않는다.
- duration 측정은 `System.nanoTime()` → ms 변환.
- `GlobalExceptionHandler`의 로그 레벨 정책:
  - 5xx 시스템 (`DataIntegrityViolationException`, `DataAccessException`, 기타 `Exception`) → `log.error("...", ex)` (stack trace 포함)
  - 5xx `CustomException`(상태 코드 ≥ 500) → `log.error("...", ex)` (stack trace 포함)
  - 4xx `CustomException`(상태 코드 < 500) → 무로그
  - `MethodArgumentNotValidException`(400) → 무로그
  - `HttpMessageNotReadableException`(400) → 무로그
  - `OptimisticLockingFailureException`(409) → `log.warn("...")` (stack trace 없음, 기존 유지)
- NaverPayGatewayImpl 라운드트립 패턴:
  - 요청 진입 시 `log.info("네이버페이 {} 요청 ...", action)` (action: 승인/취소/이력조회)
  - 성공 응답 시 `log.info("네이버페이 {} 응답 ...", action)` 또는 `log.warn(...)` (실패 응답 코드)
  - 호출 자체 예외 시 `log.warn("네이버페이 {} 호출 실패 ...", action)`

## 제약사항

- `docs/logging-conventions.md` §3·§4·§7·§8 정책 준수.
- TraceIdFilter와 책임 분리 유지 — AccessLogFilter는 액세스 로그만, MDC 조작 없음(읽기만).
- AccessLogFilter에 `@Component` 붙이지 않음 — FilterRegistrationBean으로만 등록해 중복 등록 방지(TraceIdFilter와 동일 정책).
- 외부 의존성 추가 없음 (Spring MVC/SLF4J 표준만 사용).
- `logback-spring.xml` 변경 없음 — 콘솔 패턴 `[traceId=%X{traceId:-} memberId=%X{memberId:-}]`가 이미 MDC를 자동 부착.
- Gateway의 거래 종료 ERROR 분류는 본 작업 범위 밖 — 호출자(`PaymentService`) 변경 없음.
