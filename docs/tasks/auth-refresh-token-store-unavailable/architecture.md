# 태스크 아키텍처

## 개요

Auth 도메인의 Redis 장애 처리 경로를 *application 직접 catch*에서 *infra 매핑 + presentation 응답 매핑* 패턴으로 전환한다. Order 도메인 (`order-idempotency-cache-simplification`)에서 신설된 외부 캐시 장애 처리 규약을 *fallback 가능 여부와 무관한 공통 패턴*으로 격상시키되, *application 정책 결정 분기*는 도메인별로 다르게 표현한다.

- 공통: infra adapter (`RedisRefreshTokenStore`)가 `DataAccessException`을 catch 해 도메인 예외 (`RefreshTokenStoreUnavailableException`)로 변환 + ERROR 로그.
- Auth의 application 정책: *변환 없음, 자연 propagate*. presentation 레이어 (`AuthExceptionHandler` `@RestControllerAdvice`)가 `AUTH-500-1` 응답으로 매핑.
- Order의 application 정책 (비교용, 변경 없음): 도메인 예외 catch → DB unique 안전망 경로로 fallback 진입.

## 변경 대상

| 레이어 | 변경 |
| --- | --- |
| Auth — exception | `RefreshTokenStoreUnavailableException` 신규 (`RuntimeException` 직접 상속) |
| Auth — exception | `AuthExceptionHandler` 신규 (`@RestControllerAdvice`) — 도메인-specific 인프라 장애 매핑 |
| Auth — infrastructure | `RedisRefreshTokenStore.save` / `RedisRefreshTokenStore.get`에 `DataAccessException` catch → 도메인 예외 변환 + ERROR 로그 (`@Slf4j`) |
| Auth — application | `AuthTokenIssueService`: try-catch 제거, `@Slf4j` 정리, DAO import 제거 |
| Auth — application | `AuthTokenReissueService`: try-catch 제거, `@Slf4j` 정리, DAO import 제거 |
| Test — infrastructure | `RedisRefreshTokenStoreTest` 신규 (Order adapter 테스트와 동일 구조) |
| Test — exception | `AuthExceptionHandlerTest` 신규 (`GlobalExceptionHandlerTest`와 동일 패턴) |
| Test — application | `AuthTokenIssueServiceTest`, `AuthTokenReissueServiceTest` 인프라 장애 케이스 *propagate 검증*으로 갱신 |
| Docs | `docs/exception-strategy.md` 캐시 장애 처리 섹션 정리, `docs/adr.md` task 표 행 추가 |

## 설계 방향

### 도메인 예외 신규

```java
package com.commerce.auth.exception;

public class RefreshTokenStoreUnavailableException extends RuntimeException {
    public RefreshTokenStoreUnavailableException(Throwable cause) {
        super(cause);
    }
}
```

- `RuntimeException` 직접 상속. `CustomException` 상속 금지 — 자동 매핑이 의도된 책임 분리 (presentation 매핑 명시)를 가리게 된다.

### infra adapter 매핑 — `RedisRefreshTokenStore`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    // 동일 key(refresh:{memberId})를 덮어쓰므로 기존 refresh token이 무효화된다
    @Override
    public void save(Long memberId, String refreshToken) {
        Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
        try {
            redisTemplate.opsForValue().set(buildKey(memberId), refreshToken, ttl);
        } catch (DataAccessException e) {
            log.error("refresh token 저장 실패: memberId={}", memberId, e);
            throw new RefreshTokenStoreUnavailableException(e);
        }
    }

    @Override
    public Optional<String> get(Long memberId) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(memberId)));
        } catch (DataAccessException e) {
            log.error("refresh token 조회 실패: memberId={}", memberId, e);
            throw new RefreshTokenStoreUnavailableException(e);
        }
    }

    private String buildKey(Long memberId) {
        return "refresh:" + memberId;
    }
}
```

기존 `// 동일 key... 무효화된다` 주석 위치 보존. ERROR + stack 로그로 운영 인지 가능.

### application — 자연 propagate

```java
// AuthTokenIssueService.issue
public AuthTokenIssueResult issue(Member member) {
    TokenClaims claims = TokenClaims.of(member.getId(), member.getRole());
    String accessToken = tokenIssuer.createAccessToken(claims);
    String refreshToken = tokenIssuer.createRefreshToken(claims);
    refreshTokenStore.save(member.getId(), refreshToken);
    return AuthTokenIssueResult.of(accessToken, refreshToken);
}

// AuthTokenReissueService.validateStoredRefreshToken
private void validateStoredRefreshToken(Long memberId, String refreshToken) {
    String storedRefreshToken = refreshTokenStore.get(memberId)
        .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    if (!storedRefreshToken.equals(refreshToken)) {
        throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }
}
```

- application의 인프라 장애 try-catch 제거. `RefreshTokenStoreUnavailableException`은 자연 propagate.
- `AuthException(REFRESH_TOKEN_NOT_FOUND)` 분기는 비즈니스 예외라 그대로 유지.

### presentation 신규 — `AuthExceptionHandler`

```java
package com.commerce.auth.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.commerce.common.ApiResponse;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(RefreshTokenStoreUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleRefreshTokenStoreUnavailable(
        RefreshTokenStoreUnavailableException ex
    ) {
        return ResponseEntity.status(AuthErrorCode.INTERNAL_ERROR.getStatus())
            .body(ApiResponse.error(AuthErrorCode.INTERNAL_ERROR));
    }
}
```

- 위치는 `auth.exception` 패키지 — 도메인 모듈 안에 둠. `common`이 도메인을 import 하지 않는 의존 방향 보존.
- 로그는 찍지 않는다 (infra adapter가 이미 ERROR + stack으로 기록, 중복 회피).
- `basePackages`를 좁히지 않는다 — 현재 처리 대상이 `RefreshTokenStoreUnavailableException` 한 종류라 다른 컨트롤러에 도달할 수 없다. 향후 도메인 예외가 늘면 그 시점에 범위 좁힘을 검토.

## 데이터 흐름

### 정상 흐름 — 변경 없음

```
Client → POST /api/auth/tokens
  ↓
AuthTokenIssueService.issue
  ├─ tokenIssuer.createAccessToken
  ├─ tokenIssuer.createRefreshToken
  └─ refreshTokenStore.save(memberId, refreshToken)
       └─ RedisRefreshTokenStore: redisTemplate.set(...) 성공
  ↓
200 OK (access + refresh)
```

### Redis 일시 장애 — save 실패

```
Client → POST /api/auth/tokens
  ↓
AuthTokenIssueService.issue
  ├─ tokenIssuer.create* 성공
  └─ refreshTokenStore.save
       └─ RedisRefreshTokenStore:
            ├─ DataAccessException catch
            ├─ log.error("refresh token 저장 실패: memberId={}", ...)
            └─ throw RefreshTokenStoreUnavailableException(cause)
       ↑ application: catch 없음 → 자연 propagate
       ↑ presentation: AuthExceptionHandler.handleRefreshTokenStoreUnavailable
       └─ ResponseEntity(500, AUTH-500-1)
  ↓
500 INTERNAL_ERROR (기존 응답과 동일)
```

### Redis 일시 장애 — get 실패 (토큰 재발급)

```
Client → POST /api/auth/tokens/reissue
  ↓
AuthTokenReissueService.reissue
  ├─ tokenValidator.validateRefreshToken 성공
  └─ validateStoredRefreshToken
       └─ refreshTokenStore.get
            └─ RedisRefreshTokenStore:
                 ├─ DataAccessException catch
                 ├─ log.error("refresh token 조회 실패: memberId={}", ...)
                 └─ throw RefreshTokenStoreUnavailableException(cause)
       ↑ application: catch 없음 → 자연 propagate
       ↑ presentation: AuthExceptionHandler.handleRefreshTokenStoreUnavailable
       └─ ResponseEntity(500, AUTH-500-1)
  ↓
500 INTERNAL_ERROR (기존 응답과 동일)
```

## 예외 및 실패 처리

| 케이스 | 처리 |
| --- | --- |
| `save` / `get`의 Redis 호출 실패 (`DataAccessException`) | infra adapter에서 ERROR + stack 로그, `RefreshTokenStoreUnavailableException`으로 변환 throw |
| application의 인프라 장애 | catch 하지 않음. 도메인 예외 자연 propagate |
| presentation의 `RefreshTokenStoreUnavailableException` 매핑 | `AuthExceptionHandler`가 `AUTH-500-1` 500 응답 반환. 로그 없음 (infra ERROR와 중복 회피) |
| 비즈니스 예외 (refresh token 검증 실패, 회원 미존재 등) | 기존 흐름 — `AuthException(*)` → `CustomException` 핸들러 → 4xx 응답 |

## 테스트 포인트

- `RedisRefreshTokenStore.save` 정상 — `ValueOperations.set(key, value, ttl)` 호출 검증 (key가 `refresh:{memberId}` 형식).
- `RedisRefreshTokenStore.save` 시 `DataAccessException` (예: `QueryTimeoutException`) → `RefreshTokenStoreUnavailableException`으로 변환 + cause 보존 검증.
- `RedisRefreshTokenStore.get` 정상 — 저장된 값 `Optional`로 반환.
- `RedisRefreshTokenStore.get` 시 `DataAccessException` → `RefreshTokenStoreUnavailableException` 변환 + cause 보존 검증.
- `AuthExceptionHandlerTest` — `handleRefreshTokenStoreUnavailable` 호출 시 HTTP 500 + `AUTH-500-1` 응답 검증.
- `AuthTokenIssueServiceTest` — `refreshTokenStore.save`가 `RefreshTokenStoreUnavailableException` throw 시 service가 catch 없이 그대로 propagate 함을 검증.
- `AuthTokenReissueServiceTest` — `refreshTokenStore.get` 동일.
- `grep -rn "DataAccessException" src/main/java/com/commerce/auth/` 결과: infra adapter (`RedisRefreshTokenStore`)만 출현, application 0건.
- 기존 통합 테스트 (`AuthSignUpServiceIntegrationTest` 등) 회귀 없음.
