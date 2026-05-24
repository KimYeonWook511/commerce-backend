# 로깅 컨벤션

이 문서는 백엔드 application 로그(`com.commerce.*`)의 작성·운영 규칙을 정의한다. Epic "운영용 로깅 체계 도입"의 기준 문서이며, 후속 작업(logback 설정, MDC Filter, 도메인 로깅 보강 등)이 이 문서의 정책을 따른다.

## 1. 목적과 범위

### 정하는 것
- 로그 레벨 기준 (ERROR/WARN/INFO/DEBUG)
- 레이어별 로그 책임 (Filter/Controller/Application/Domain/Infrastructure)
- 예외 로깅 표준 (4xx/5xx 분리, stack trace 포함 시점)
- 민감 정보 마스킹 (GDPR/PIPA 기반)
- 로거 네이밍·메시지 작성·MDC 운영 규칙
- 출력 포맷 (콘솔 텍스트 / 파일 JSON)

### 정하지 않는 것
- `logback-spring.xml` 설정 구체값 (환경별 ROOT 레벨 수치, appender 구성, 파일 롤링 정책 등) — 별도 후속 작업
- traceId Filter·MDC 도입 구현 — 별도 후속 작업
- 중앙 로그 수집 인프라(ELK, Loki 등) — Epic 비목표
- 로그 보관 기간 구체 일수 — 운영 파이프라인 작업에서 결정

### 본 문서의 적용 대상
- **대상**: 우리 application 로그 (`com.commerce.*`)
- **범위 밖**: Tomcat access log, Hibernate SQL 출력, Spring framework 시작 로그 등 framework 로그 — `logback-spring.xml`에서 별도 logger 레벨로 침묵·노이즈 컨트롤

## 2. 로그 레벨 기준

| 레벨 | 사용 기준 |
|------|----------|
| **ERROR** | 5xx 시스템 예외(DB 무결성, DataAccess 부모, NPE 등), 보상 흐름의 1차 예외, 외부 호출 완전 실패로 거래가 종료된 경우 |
| **WARN** | **운영 주목이 필요한** 4xx(반복 401·403, 결제 검증 실패 등), 보상 흐름의 2차 예외(덜 중요), 외부 호출 retry, `OptimisticLockingFailureException` 409 |
| **INFO** | 도메인 비즈니스 이벤트 — 상태 전환(주문 생성/취소, 결제 승인/취소, 회원 가입, 재고 차감/복구 등) |
| **DEBUG** | 외부 API 요청·응답 본문, SQL 파라미터, 로컬·테스트 진단, Filter body 캐싱 옵션. **5장의 마스킹 패턴이 그대로 적용된다**(password·token 평문 노출 차단) |

### 4xx 처리 원칙
일상 비즈니스 4xx(재고 부족, 존재하지 않는 상품 조회, 이미 사용된 쿠폰 등)는 로그를 남기지 않는다. `GlobalExceptionHandler`가 일괄 판단해 운영 모니터링이 필요한 건만 WARN으로 분류한다. WARN 대상 4xx 잠정 목록은 4장 참조.

### 환경별 ROOT 레벨 원칙
구체 수치(local=DEBUG, prod=INFO 등)는 `logback-spring.xml`이 단일 진실의 원천이다. 본 문서는 다음 원칙만 정의한다.

- **local·test**: 더 상세하게 (디버깅·테스트 실패 원인 분석)
- **prod**: 더 조용하게 (디스크·노이즈 최소화)
- **외부 라이브러리**: noisy logger(`org.hibernate.SQL`, `io.netty`, Kafka consumer 등)는 운영에서 침묵 처리

## 3. 레이어별 로그 정책

| 레이어 | 로그 책임 | 예시 |
|--------|----------|------|
| **Filter/Interceptor** | HTTP 메타데이터 일괄(method, path, status, latency, traceId, memberId). body는 안 남김(마스킹·메모리 부담) | `REQUEST POST /api/orders traceId=abc memberId=42` / `RESPONSE 201 latency=88ms` |
| **Presentation (Controller)** | 직접 로그 남기지 않음 (얇은 위임 레이어) | — |
| **Application (Service)** | 유스케이스 시작·완료의 도메인 이벤트 INFO. 핵심 파라미터를 의미 있는 필드로 표현 | `log.info("주문 생성 orderId={} memberId={} itemCount={}", ...)` |
| **Domain** | 로그 없음 (순수 도메인 보호, SLF4J 의존 금지) | — |
| **Infrastructure** | 외부 호출(HTTP/DB/Kafka/Redis)의 실패·retry. 본문 디버깅은 DEBUG | `log.warn("naverpay http 5xx retry attempt={}", ...)` |

### body 처리
운영 액세스 로그에는 요청·응답 body를 남기지 않는다. 디버깅이 필요한 경우 Filter 옵션으로 DEBUG 레벨에서 켤 수 있게 한다(운영 OFF, 로컬 ON).

도메인 파라미터의 의미 있는 필드는 Application 레이어가 INFO 로그로 표현한다 — body 통째 로깅보다 의미가 명확하고 마스킹 부담도 줄어든다.

## 4. 예외 로깅 표준

### 처리 위치
`GlobalExceptionHandler`에서 일괄 처리한다. Application·Adapter는 직접 로그를 남기지 않는다 (보상 catch의 1차/2차 예외는 별도, 아래 참조).

### Stack trace 포함 정책

| 케이스 | 레벨 | Stack trace | 남길 필드 |
|--------|------|-------------|---------|
| 5xx 시스템 (`DataIntegrityViolationException` 등) | ERROR | ✅ 전체 | message, code, traceId, memberId, stack |
| 보상 1차 예외 | ERROR | ✅ 전체 | message, traceId, stack |
| Composite Exception (치명적 2차) | ERROR | ✅ 전체 + `suppressed[]` | message, suppressed traces |
| 운영 주목 4xx (WARN 대상) | WARN | ❌ | code, message, traceId, memberId, 핵심 컨텍스트 |
| 보상 2차 예외 (덜 중요) | WARN | ❌ | message, traceId |
| 외부 호출 retry | WARN | ❌ | attempt, target, message |
| `OptimisticLockingFailureException` | WARN | ❌ | code(`COMMON-409-1`), traceId |

### WARN 대상 4xx 운영 모니터링 목록 (잠정)
- 인증·인가 반복 실패 (401·403 연속) — 부정 의심
- 결제 검증·서명 실패 — PG 연동 이슈 추적

그 외 도메인 예외는 `GlobalExceptionHandler`의 분류에 따라 무로그로 처리한다. 구체 목록은 후속 작업에서 재확인한다.

### 예외 인자 처리 (SLF4J 관례)
SLF4J는 마지막 인자가 `Throwable`이면 자동으로 stack trace를 출력한다. 이 관례를 따른다.

```java
// 올바름
log.error("주문 처리 실패 orderId={}", orderId, throwable);

// 금지 — stack 손실
log.error("실패: " + e.getMessage());
log.error("...", e.toString());

// 금지 — stderr로 빠져 로그 시스템 우회
e.printStackTrace();
```

### 1차/2차 예외 규약
보상 catch 안에서의 1차/2차 예외 처리 규약은 [exception-strategy.md "보상 catch 2차 예외 처리"](exception-strategy.md) 절을 참조한다. 본 문서에서는 레벨만 요약한다.

- **1차 예외**: catch 진입 즉시 `log.error()` — 근본 원인 보존
- **2차 예외 (덜 중요)**: `log.warn()` + 1차 예외 전파
- **2차 예외 (치명적)**: Composite Exception(`addSuppressed`)으로 1차·2차 둘 다 전파

## 5. 민감 정보 마스킹

GDPR(Article 4·5)·개인정보보호법(PIPA)의 data minimization, purpose limitation, storage limitation 원칙을 따른다.

### 데이터 분류

| 필드 | 분류 | 처리 |
|------|------|------|
| 이메일 | PII (직접 식별자) | **원칙: 로그에 안 남김**. memberId로 대체 |
| 비밀번호 | 인증 정보 (PII 이상) | 평문·해시 모두 로그 금지 |
| JWT / access token | 인증 정보 (PII 이상) | 평문 금지, `Authorization` 헤더는 Filter에서 통째 제거 또는 `Bearer ***`로 대체 |
| memberId (PK) | pseudonymous identifier | **로그의 기본 식별자**. 직접 식별자(이메일 등)를 대체하여 활용 |

### 정책

1. **사용자 식별은 memberId로 통일**한다. Application·Filter·Infrastructure 모든 로그에서 `memberId=42` 형태로 남긴다. 이메일은 원칙적으로 로그에 안 남긴다(data minimization).
2. **이메일이 어쩔 수 없이 들어가는 케이스**(예: 로그인 시도 실패로 memberId가 아직 없을 때)는 부분 마스킹 `a***@b.com`을 적용한다. 가능한 한 피한다.
3. **비밀번호·토큰은 이중 방어**한다.
   - **(규율)** 개발자가 코드에서 password·token 필드를 `log`·MDC·메시지에 절대 넣지 않는다. PR 리뷰에서 차단한다.
   - **(자동)** Logback 인코더에 마스킹 패턴을 등록한다 — `password=*`, `token=*`, `accessToken=*`, `refreshToken=*` 등 실수로 노출된 경우 자동 대체한다.
4. **Authorization 헤더**는 Filter에서 access log 작성 시 통째로 제거하거나 `Bearer ***`로 대체한다.
5. **보관 기간**은 무기한 보관하지 않는다. 구체 일수는 운영 로그 파이프라인 작업에서 결정한다.

## 6. 로거 네이밍 관례

- **`@Slf4j` (Lombok) 우선** 사용한다. 현재 코드의 일관된 패턴이다.
- Lombok 비적용 클래스(테스트 유틸·`record` 등)에서만 `LoggerFactory.getLogger(...)`를 사용한다.
- 로거 이름은 클래스 FQN(기본값) 그대로 사용한다. 별도 지정하지 않는다.

```java
@Slf4j
@Service
public class CreateOrderService {
    public Order create(...) {
        log.info("주문 생성 orderId={} memberId={}", orderId, memberId);
        ...
    }
}
```

## 7. 메시지 작성 규칙

### 언어
로그 메시지 본문은 **한국어**로 작성한다. 도메인 식별자(`orderId`, `memberId`, `paymentId` 등)는 영어 그대로 사용한다.

```java
// 올바름
log.info("주문 생성 orderId={} memberId={} itemCount={}", orderId, memberId, itemCount);
log.warn("결제 검증 실패 merchantPayKey={} reason={}", merchantPayKey, reason);
```

### 포맷
SLF4J placeholder `{}`를 사용한다. 문자열 concatenation은 금지한다.

```java
// 올바름
log.info("주문 생성 orderId={}", orderId);

// 금지 — 성능·NPE 방어 측면에서 placeholder가 우수
log.info("주문 생성 orderId=" + orderId);
```

### 이벤트 패턴
도메인 이벤트는 **명사형 + 상태/동사** 패턴으로 작성한다. grep으로 추적 가능하게 일관성을 유지한다.

| 좋은 예 | 의미 |
|---------|------|
| `주문 생성` | 주문 entity 생성 완료 |
| `결제 승인 완료` | Payment SUCCEEDED 전환 |
| `재고 차감 실패` | 재고 부족 등으로 차감 실패 |
| `naverpay http 5xx retry attempt=2` | PG 호출 재시도 진입 |

## 8. MDC 운영

### 설정
Filter가 요청 진입 시 다음을 MDC에 push한다.

- `traceId`: 요청 단위 추적 ID (모든 요청)
- `memberId`: 사용자 식별자 (인증된 경우만)

### 정리
요청 종료 시 **반드시 `MDC.clear()`를 호출**한다. Filter의 `finally` 블록 책임이다. 안 하면 스레드 풀에서 다음 요청에 누적되어 잘못된 traceId·memberId가 남는다.

### 도메인 확장
`orderId`, `paymentId` 등 도메인 식별자는 유스케이스 진입 시 push하고 종료 시 remove한다. 도메인별로 후속 작업에서 확장된다.

### 비동기·이벤트 경계의 traceId 전파

#### Kafka 경계 (구현 완료)

Kafka producer/consumer 경계는 `ProducerInterceptor` + `RecordInterceptor` 조합으로 traceId를 전파한다.

- **producer**: `TraceIdKafkaProducerInterceptor.onSend()`가 MDC `traceId`를 헤더 `X-Trace-Id`에 부착. MDC에 유효한 traceId가 없으면 신규 UUID 발급.
- **consumer**: `TraceIdRecordInterceptor.intercept()`가 헤더 `X-Trace-Id`를 읽어 MDC에 push. 헤더가 없거나 유효하지 않으면 신규 UUID 발급. `success`/`failure` 콜백에서 `MDC.remove("traceId")`로 정리.
- **등록**: `TraceIdKafkaConfig` — `DefaultKafkaProducerFactoryCustomizer` Bean(producer factory) + `TraceIdRecordInterceptor` Bean(consumer factory 주입)
- **DLT**: `DeadLetterPublishingRecoverer`가 동일 KafkaTemplate을 사용하므로 DLT 발행 시에도 traceId 헤더 자동 전파.

#### @Async, @TransactionalEventListener 경계 (미구현)

`@Async` 및 `@TransactionalEventListener(AFTER_COMMIT)`의 비동기 전환 시 MDC 전파는 별도 후속 작업에서 다룬다(`TaskDecorator`, `ApplicationEventMulticaster` wrapping 등).

## 9. 포맷 정책

| 출력 채널 | 포맷 | 이유 |
|----------|------|------|
| **콘솔 (stdout)** | 텍스트 (`%d %level [%logger] [%X{traceId}] - %msg`) | 사람이 읽기 쉬움. local 개발 + 컨테이너 stdout 캡처 |
| **파일** | JSON (one-line per event) | 구조화된 로그. 후속 수집 파이프라인 도입 시 파싱 비용 0 |

환경별 활성(어떤 환경에서 콘솔/파일 중 무엇을 켤지)은 `logback-spring.xml`에서 결정한다. 본 문서는 두 채널의 포맷 정책만 정의한다.

### JSON 공통 필드

| 필드 | 설명 |
|------|------|
| `timestamp` | ISO-8601 **UTC** (예: `2026-05-22T01:23:45.678Z`). 타임존 혼동 방지 |
| `level` | ERROR / WARN / INFO / DEBUG |
| `logger` | 클래스 FQN |
| `thread` | 실행 스레드 이름 |
| `message` | 로그 메시지 (한국어) |
| `traceId` | 요청 단위 추적 ID (MDC) |
| `memberId` | 사용자 식별자 (MDC, 인증된 요청만) |
| `exception` | (선택) `{class, message, stackTrace}` — ERROR + stack trace 케이스만 |

도메인별 추가 MDC(`orderId`, `paymentId` 등)는 필요 시 JSON 상위 필드로 자연스럽게 직렬화된다.

## 10. 참조

- 예외 처리 정책 (find-first, 안전망 계층, 보상 catch 2차 예외 처리): [exception-strategy.md](exception-strategy.md)
- 백엔드 구조와 의존성: [architecture.md](architecture.md)
- 테스트 컨벤션: [testing-conventions.md](testing-conventions.md)
