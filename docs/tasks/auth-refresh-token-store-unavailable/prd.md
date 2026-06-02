# 태스크 PRD

## 태스크명

- `auth-refresh-token-store-unavailable`

## 배경

- PR #180 (`order-idempotency-cache-simplification`)에서 외부 캐시(Redis) 장애 처리 규약이 신설됐다. infra adapter가 `DataAccessException`을 catch 해 도메인 예외(`OrderIdempotencyStoreUnavailableException`)로 변환하고, application이 이를 catch 해 fallback 분기에 진입한다. `docs/exception-strategy.md`의 *Redis 캐시 장애 처리* 섹션이 이 패턴을 정리한다.
- Auth 도메인(`AuthTokenIssueService:33`, `AuthTokenReissueService:65`)은 *application에서 직접* `DataAccessException` catch + `AuthException(INTERNAL_ERROR)` 변환 패턴을 유지 중. 동작 결함은 없지만 application이 Spring `DataAccessException`에 직접 의존해 port 추상화가 약하다.
- `docs/exception-strategy.md`의 *비교: fallback 불가한 경우* 섹션이 Auth를 *예외 케이스*로 남겨두고 있어 규약이 *공통 매핑 패턴*으로 격상되지 못한다.
- Issue #181 — 두 패턴을 *fallback 가능성과 무관한 동일 매핑 패턴*으로 통일해 application의 Spring DAO 의존을 제거하고, 미래 사용처(Payment, Stock 등) 추가 시 일관성 부담이 누적되지 않도록 한다.

## 목표

- Auth 도메인의 Redis 장애 처리도 *infra adapter의 도메인 예외 매핑 + presentation의 응답 매핑* 패턴으로 전환한다.
- application 계층에서 `org.springframework.dao.DataAccessException` 의존을 제거하고, 인프라 장애에 대한 *단순 변환 try-catch* 보일러플레이트도 함께 제거한다.
- 응답 동작은 그대로 보존한다 — Redis 장애 시 500 `AUTH-500-1` 응답 유지.
- `docs/exception-strategy.md`의 캐시 장애 처리 규약을 *fallback 가능 여부에 무관한 공통 패턴*으로 정리한다.
- *도메인-specific 인프라 장애*를 도메인 모듈 안의 `@RestControllerAdvice`가 매핑하는 컨벤션을 신규 도입한다. `common` 모듈이 도메인을 import 하는 역방향 의존을 회피한다.

## 범위

### 포함 범위

- 신규 도메인 예외 `RefreshTokenStoreUnavailableException` (`RuntimeException` 직접 상속).
- `RedisRefreshTokenStore.save` / `RedisRefreshTokenStore.get`에 `DataAccessException` catch → 도메인 예외 변환 + ERROR 로그 추가 (`@Slf4j`).
- `AuthTokenIssueService`, `AuthTokenReissueService`의 인프라 장애 try-catch **전부 제거**. 도메인 예외는 자연 propagate.
- 신규 `auth.exception.AuthExceptionHandler` (`@RestControllerAdvice`) — `RefreshTokenStoreUnavailableException`을 `AUTH-500-1` 응답으로 매핑.
- 신규 단위 테스트 `RedisRefreshTokenStoreTest`, `AuthExceptionHandlerTest`.
- 기존 단위 테스트(`AuthTokenIssueServiceTest`, `AuthTokenReissueServiceTest`)의 인프라 장애 케이스를 *예외 그대로 propagate 검증*으로 갱신.
- `docs/exception-strategy.md`의 *Redis 캐시 장애 처리* 섹션을 fallback 가능 여부와 무관한 공통 매핑 패턴으로 정리.
- `docs/ADR.md` task 표에 `auth-refresh-token-store-unavailable` 행 추가.
- 회고록 작성.

### 제외 범위

- `RefreshTokenStore` port 시그니처 변경 (도메인 예외는 unchecked라 선언 불필요).
- `AuthErrorCode.INTERNAL_ERROR` 매핑/응답 코드 변경.
- `common.exception.GlobalExceptionHandler` 수정 — common의 도메인 의존 회피 원칙 유지.
- 베이스 클래스(`StoreUnavailableException` 등) 추출 — YAGNI. Payment/Stock 등 사용처 3곳 이상 등장 시 별도 검토.
- Order 도메인 패턴 수정 — Order는 fallback 가능한 케이스로 application catch 패턴이 정답.
- 다른 (머지 완료된) task 폴더 문서 수정.
- `commerce-workspace/docs/` 하위 문서 (frontend 세션 책임).

## 주요 시나리오

1. **정상 토큰 발급/재발급**
   - `AuthTokenIssueService.issue` → `refreshTokenStore.save` 성공 → 200 응답 (기존과 동일).
   - `AuthTokenReissueService.reissue` → `refreshTokenStore.get` 성공 → 200 응답 (기존과 동일).
2. **Redis 일시 장애 — 토큰 발급**
   - `RedisRefreshTokenStore.save` 내부 `DataAccessException` → adapter가 `RefreshTokenStoreUnavailableException`으로 변환하며 ERROR 로그.
   - `AuthTokenIssueService`는 catch 하지 않음 → 도메인 예외가 그대로 propagate.
   - `AuthExceptionHandler`가 받아 `AUTH-500-1` 500 응답 반환 (기존과 동일).
3. **Redis 일시 장애 — 토큰 재발급**
   - `RedisRefreshTokenStore.get` 내부 `DataAccessException` → adapter가 도메인 예외 변환 + ERROR 로그.
   - `AuthTokenReissueService.validateStoredRefreshToken`도 catch 하지 않음 → propagate.
   - `AuthExceptionHandler`가 받아 `AUTH-500-1` 500 응답 (기존과 동일).

## 요구사항

- Auth application 계층 (`src/main/java/com/commerce/auth/application/`)에 `org.springframework.dao.DataAccessException` import가 존재하지 않아야 한다.
- Auth application 계층에 `RefreshTokenStoreUnavailableException` catch가 존재하지 않아야 한다 (자연 propagate).
- Redis 장애 시 운영자가 인지할 수 있도록 infra adapter가 ERROR + stack 로그를 남긴다. `AuthExceptionHandler`는 로그를 남기지 않는다 (중복 회피).
- 기존 응답 코드/메시지 그대로 보존: Redis 장애 시 500 `AUTH-500-1 INTERNAL_ERROR`.
- `RefreshTokenStore` port 시그니처는 변경하지 않는다.
- `RedisRefreshTokenStoreTest`는 Order adapter 테스트 (`RedisOrderIdempotencyStoreTest`)와 동일 구조.
- `AuthExceptionHandlerTest`는 `GlobalExceptionHandlerTest`와 동일 패턴 (handler를 `new` 해서 단위 테스트).

## 제약사항

- Order 도메인의 기존 패턴 (`OrderIdempotencyStoreUnavailableException` / `RedisOrderIdempotencyStore` / `OrderCreateService.createOrder`)은 수정하지 않는다.
- `common.exception.GlobalExceptionHandler`는 수정하지 않는다 — common이 도메인을 import 하는 역방향 의존을 회피.
- `commerce-workspace/docs/` 하위 문서는 수정하지 않는다 (frontend 세션 책임).
- 머지 완료된 task 폴더 문서는 수정하지 않는다.
- 동작 변경 없는 리팩터링이므로 외부 동시성/통합 테스트 신규 추가는 없다.
