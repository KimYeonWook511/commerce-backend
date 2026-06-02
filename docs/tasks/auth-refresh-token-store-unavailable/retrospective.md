# 회고록: auth-refresh-token-store-unavailable

## 1. 배경

### Issue #181 — Auth의 Redis 장애 처리 패턴이 규약과 달라 격상이 막힌 상황

PR #180(`order-idempotency-cache-simplification`)에서 외부 캐시 장애 처리 규약이 신설됐다. infra adapter가 `DataAccessException`을 도메인 예외로 변환하고 application이 그 도메인 예외를 catch해 정책 결정을 내리는 구조다. `docs/exception-strategy.md`의 *Redis 캐시 장애 처리* 섹션이 이 패턴을 정리했다.

Auth 도메인은 이 패턴을 따르지 않고 *application에서 직접* `DataAccessException`을 catch해 `AuthException(INTERNAL_ERROR)`로 변환하는 방식을 유지 중이었다. `docs/exception-strategy.md`는 Auth를 *비교: fallback 불가한 경우*라는 예외 케이스로 기술해뒀다. 결과적으로 캐시 장애 처리 규약이 *모든 사용처가 따르는 공통 규약*으로 격상되지 못하고 있었다.

동작 결함은 없었다. Redis 장애 시 응답이 기존과 동일하게 500 `AUTH-500-1`로 반환됐다. 그러나 application 계층이 Spring `DataAccessException`에 직접 의존하는 문제가 있었고, 미래 Payment나 Stock 같은 외부 캐시 사용처가 추가될 때마다 *Auth처럼 할지 Order처럼 할지*를 매번 판단해야 하는 일관성 부담이 누적된다는 우려가 있었다.

---

## 2. 결정 과정 요약

### (ADR-1) 매핑 패턴 통일 vs 현행 유지

fallback 가능 여부를 *구조 분기점*으로 둘지(현행 유지), *application/presentation 정책 결정 분기*로 격하할지(패턴 통일)를 비교했다.

현행 유지 입장은 Auth는 fallback 불가하므로 application 직접 catch가 구조적으로 자연스럽다는 해석이었다. 패턴 통일 입장은 *매핑 단계(infra adapter)*는 공통 규약으로, *catch 위치와 정책 결정*은 도메인별로 분기시키면 application의 Spring DAO 의존을 끊으면서도 규약이 성립한다는 입장이었다.

패턴 통일(B 안) 채택. 도메인 예외 신설(`RefreshTokenStoreUnavailableException`) 후 infra adapter가 `DataAccessException`을 catch해 도메인 예외로 변환하는 구조로 전환했다. catch 위치와 정책 결정 방식은 ADR-2, ADR-3에서 별도 결정.

### (ADR-2) catch 위치 결정 — application vs presentation

- **A 안**: application이 도메인 예외를 catch해 `AuthException(INTERNAL_ERROR)`로 변환. `GlobalExceptionHandler.handleCustomException`이 자동 매핑해 500 응답. Order와 구조적으로 유사.
- **B 안**: application은 catch하지 않는다. 도메인 예외가 그대로 propagate되어 `@RestControllerAdvice`가 응답을 매핑.

A 안은 두 가지 문제가 있었다. 첫째, Auth의 application catch 본문이 *단순 변환 두 줄* 이라 Order와 달리 *정책 결정 사실*을 표현하는 가치가 없다. Order는 *fallback 진입*이라는 정책 결정이 있어 catch가 의미를 갖지만, Auth에는 그런 정책이 없다. 둘째, `AuthException`은 *auth 비즈니스 예외*(`TOKEN_INVALID`, `REFRESH_TOKEN_NOT_FOUND` 등) 의미다. 인프라 장애를 비즈니스 예외로 감싸는 건 시멘틱적으로 부정확하다.

동시에 application의 인프라 장애 `log.error` 제거도 결정됐다. infra adapter가 동일 사실을 ERROR + stack으로 기록하므로 중복이다. 같은 장애에 ERROR 로그가 두 번 찍히면 운영 추적이 오히려 어려워진다.

B 안 채택. application에서 catch 없이 도메인 예외를 자연 propagate하고 presentation의 `@RestControllerAdvice`가 응답을 매핑한다.

### (ADR-3) `@RestControllerAdvice` 위치 결정 — `GlobalExceptionHandler` vs 도메인 advice

- **(a) 안**: `common.exception.GlobalExceptionHandler`에 `@ExceptionHandler(RefreshTokenStoreUnavailableException.class)` 한 줄 추가. 기존 단일 advice 패턴 유지.
- **(b) 안**: `auth.exception.AuthExceptionHandler` (`@RestControllerAdvice`) 신설. 도메인 모듈 안에 매핑 책임을 두고 `common`이 도메인을 import하지 않게 보존.
- **(c) 안**: `RefreshTokenStoreUnavailableException extends CustomException`으로 만들어 자동 매핑. 추가 핸들러 불필요.

(c) 안은 ADR-1 채택 사유(`CustomException` 상속 시 `GlobalExceptionHandler.handleCustomException`가 자동 응답 매핑되어 application catch 의도를 우회할 수 있는 구조)와 충돌해 비채택.

(a) 안은 `common → auth` 역의존을 신설한다. 도메인이 늘어날수록 `common`이 모든 도메인 예외를 import하게 되어 부담이 누적된다.

(b) 안은 *도메인별 advice* 컨벤션을 새로 도입하는 부담이 있지만 의존 방향이 정합(`auth → common`)하고 확장성도 명확하다.

(b) 안 채택. `com.commerce.auth.exception.AuthExceptionHandler`를 `@RestControllerAdvice`로 신설했다.

### (ADR-4) 베이스 클래스 추출 여부

`OrderIdempotencyStoreUnavailableException`과 `RefreshTokenStoreUnavailableException`이 같은 구조(`RuntimeException(Throwable cause)`)를 갖는다. 공통 베이스 클래스(`StoreUnavailableException`) 추출 여부를 검토했다.

YAGNI로 거부. 사용처가 2곳뿐이고, Order는 application catch, Auth는 presentation catch라 *공통 catch 시나리오*가 없다. Order와 Auth가 같은 베이스를 공유하면 두 도메인 간 우연한 결합이 생긴다. Payment/Stock 등 외부 캐시 사용처가 3곳 이상 등장하고 공통 catch 시나리오가 실제로 필요해지는 시점에 재검토한다.

### port 시그니처 변경 여부

`RefreshTokenStore`에 `throws RefreshTokenStoreUnavailableException`을 선언할지 검토했다. unchecked 예외라 선언이 불필요하고, port가 도메인 예외 존재를 의식하지 않는 게 추상화 보존에 유리하다. 변경하지 않기로 결정.

---

## 3. 핵심 트레이드오프

| 결정 | 얻은 것 | 감수한 것 |
| --- | --- | --- |
| 매핑 패턴 통일 (ADR-1) | application의 Spring DAO 의존 제거, 미래 사용처 추가 시 의사결정 비용 감소, 공통 규약 격상 | 도메인 예외 클래스 1개 추가, `docs/exception-strategy.md` 캐시 장애 처리 섹션 재정리 필요 |
| catch 위치를 presentation으로 (ADR-2) | application happy path만 보임, 단순 변환 보일러플레이트 제거, 중복 로그 회피, `AuthException` 의미 정합 | Order와 catch 위치 자체가 갈라져 두 패턴을 명시적으로 설명해야 함 |
| 도메인-specific `@RestControllerAdvice` (ADR-3) | `common` → 도메인 역의존 회피, 도메인별 응답 정책 응집, 확장성 명확 | 단일 advice 컨벤션에서 도메인별 advice 컨벤션으로의 전환 부담, 미래 advice 우선순위/범위 한정 검토 필요 |
| 베이스 클래스 추출 보류 (ADR-4) | 과한 추상화 회피, 도메인 격리 보존, CLAUDE.md 원칙 정합 | 미래 추가 시 부모 추출 + import 조정 비용 (변경 범위 작고 IDE 지원 가능하여 비용 낮음) |

---

## 4. 변경 범위

- **신규**: `RefreshTokenStoreUnavailableException` (RuntimeException 직접 상속), `AuthExceptionHandler` (@RestControllerAdvice), `RedisRefreshTokenStoreTest`, `AuthExceptionHandlerTest`.
- **수정**: `RedisRefreshTokenStore` — `@Slf4j` 추가, `save`/`get`에 `DataAccessException` catch + ERROR 로그 + 도메인 예외 변환. `AuthTokenIssueService` — try-catch 제거, DAO import 제거. `AuthTokenReissueService` — try-catch 제거, DAO import 제거. `AuthTokenIssueServiceTest`, `AuthTokenReissueServiceTest` — 인프라 장애 케이스를 propagate 검증으로 갱신.
- **문서**: `docs/exception-strategy.md` 캐시 장애 처리 섹션을 fallback 가능 여부와 무관한 공통 매핑 패턴으로 재정리. catch 위치 분기(application vs presentation), 로깅 규약 fallback 불가 케이스 항목 신규 추가. `docs/ADR.md` Task ADR 색인 표에 `auth-refresh-token-store-unavailable` 행 추가.

---

## 5. 부수 이슈 처리

- 본 task 머지 시 Issue #181 close.
- `OrderIdempotencyStoreUnavailableException`과의 공통 베이스 클래스 추출은 Payment/Stock 등 외부 캐시 사용처가 3곳 이상 등장하고 공통 catch 시나리오가 필요해진 시점의 *별도 task*로 미룬다.
- `AuthExceptionHandler`의 우선순위(`@Order`)와 범위 한정(`basePackages`)은 사용처가 늘어났을 때 별도 검토한다. 현 시점에는 `RefreshTokenStoreUnavailableException` 단일 예외 처리라 충돌이 없다.

---

## 6. 미래 결정 시점

다음 사건이 발생하면 본 task 결정을 재검토한다.

- **외부 캐시 사용처가 3곳 이상 등장** → 베이스 클래스 추출 검토 (ADR-4 재검토).
- **Auth 도메인에 fallback 가능한 캐시 사용처 등장** (예: 토큰 검증 캐시) → catch 위치 결정 재검토. fallback 진입은 application catch가 정답이므로 Auth에도 Order와 같은 application catch 패턴을 부분 도입해야 한다 (ADR-2 재검토).
- **다른 도메인(Order/Payment 등)이 도메인-specific 인프라 장애 직접 매핑 필요** → 자체 `@RestControllerAdvice` 신설로 같은 컨벤션 확장 (ADR-3 재검토).
- **도메인 advice가 여러 개 등록되어 우선순위 충돌 발생** → `@Order` / `basePackages` 정책 도입 검토.
- **infra adapter의 ERROR 로그만으로 운영 인지가 부족한 사례 발견** → 핸들러 로그 도입 또는 메트릭 도입 검토.

---

## 7. 배운 점

- **패턴 분기점은 구조가 아니라 정책 결정 내용일 수 있다.** fallback 가능 여부를 *구조 분기*로 두면 사용처 추가 시 의사결정 비용이 누적된다. *catch 위치 결정 분기*로 격하하면 매핑 구조 자체는 공통 규약으로 격상 가능하고, 도메인별 정책 차이는 catch 위치에서만 드러난다.
- **인프라 장애를 비즈니스 예외로 감싸지 않는 게 시멘틱적으로 정직하다.** application의 *단순 변환 두 줄*은 인프라 장애를 `AuthException`이라는 비즈니스 옷에 입혀 GlobalHandler에 보내는 구조였다. 인프라 장애는 도메인 예외 그대로 presentation까지 올려서 매핑하는 게 책임 분리에 부합한다.
- **common 모듈의 도메인 의존을 회피하는 가치는 사용처가 늘수록 커진다.** 한 사례만 보면 `common`에 핸들러 한 줄 추가가 가장 작은 변경이다. 그러나 사용처가 늘어났을 때 `common`이 모든 도메인을 import하게 되는 누적 부담을 피하려면 도메인-specific advice 컨벤션을 일찍 도입하는 게 낫다.
- **베이스 클래스 추출은 사용처 N=2 에서는 과하다.** IDE 지원과 변경 범위가 작아 N≥3 진입 시점에 추출해도 비용 차이가 미미하다. YAGNI 는 예외 클래스 설계에도 그대로 적용된다.
- **중복 로그는 운영 추적을 오히려 어렵게 한다.** 같은 장애에 infra ERROR + application ERROR가 각각 찍히면 stack trace도 두 번 남아 어느 것이 근원인지 파악이 느려진다. 로그 위치를 *기술적 사실을 처음 인지한 지점*(infra adapter)으로 단일화하면 추적이 단순해진다.
