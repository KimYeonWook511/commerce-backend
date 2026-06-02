# Step 1: auth-store-unavailable-mapping

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 설계 의도와 결정 근거를 파악하라:

- `/docs/tasks/auth-refresh-token-store-unavailable/prd.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/architecture.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/adr.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/api-spec.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/db-schema.md`

기존 결정 맥락 (의미 비교용, 동일 매핑 패턴 참고):

- `/docs/exception-strategy.md` *Redis 캐시 장애 처리* 섹션 (PR #180 신설)
- `/docs/tasks/order-idempotency-cache-simplification/adr.md` ADR-1 (Redis 장애 시 도메인 예외 매핑 + application fallback)

수정 대상 코드 (현재 상태 파악):

- `/src/main/java/com/commerce/auth/application/AuthTokenIssueService.java`
- `/src/main/java/com/commerce/auth/application/AuthTokenReissueService.java`
- `/src/main/java/com/commerce/auth/application/port/RefreshTokenStore.java`
- `/src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`
- `/src/main/java/com/commerce/auth/infrastructure/jwt/JwtProperties.java`
- `/src/main/java/com/commerce/auth/exception/AuthException.java`
- `/src/main/java/com/commerce/auth/exception/AuthErrorCode.java`
- `/src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java`
- `/src/main/java/com/commerce/common/ApiResponse.java`
- `/src/test/java/com/commerce/auth/application/AuthTokenIssueServiceTest.java`
- `/src/test/java/com/commerce/auth/application/AuthTokenReissueServiceTest.java`
- `/src/test/java/com/commerce/common/exception/GlobalExceptionHandlerTest.java`

참고 코드 (동일 매핑 패턴):

- `/src/main/java/com/commerce/order/exception/OrderIdempotencyStoreUnavailableException.java`
- `/src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`
- `/src/test/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStoreTest.java`

## 작업

본 step은 Auth 도메인의 Redis 장애 처리를 *infra adapter의 도메인 예외 매핑 + presentation의 응답 매핑* 패턴으로 전환하는 *사용자 기능 단위* 변경이다. 도메인 예외 신설, infra adapter 매핑, application try-catch 제거, presentation `@RestControllerAdvice` 신설, 단위 테스트 갱신을 한 step 안에서 자기완결적으로 처리한다.

### 1. 신규 — `RefreshTokenStoreUnavailableException`

`src/main/java/com/commerce/auth/exception/RefreshTokenStoreUnavailableException.java`

```java
package com.commerce.auth.exception;

/**
 * refresh token 저장소(Redis)가 일시적으로 사용 불가함을 표현하는 예외.
 * AuthExceptionHandler가 받아 AUTH-500-1 응답으로 매핑한다.
 */
public class RefreshTokenStoreUnavailableException extends RuntimeException {

    public RefreshTokenStoreUnavailableException(Throwable cause) {
        super(cause);
    }
}
```

제약:
- `RuntimeException` 직접 상속. `CustomException` 상속 금지. 자동 매핑이 의도된 책임 분리 (presentation 매핑 명시)를 가리게 되고, 미래에 fallback 분기가 도입될 때 catch 의도가 우회될 위험이 있다 (ADR-1).
- 생성자는 `Throwable cause` 한 개만.

### 2. 변경 — `RedisRefreshTokenStore`에 `DataAccessException` 매핑 추가

`src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`

`save`와 `get` 모두 `DataAccessException` catch를 추가한다. ERROR 로그 + 도메인 예외 throw.

```java
package com.commerce.auth.infrastructure;

import java.time.Duration;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.exception.RefreshTokenStoreUnavailableException;
import com.commerce.auth.infrastructure.jwt.JwtProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

제약:
- 기존 `// 동일 key... 무효화된다` 주석은 위치 그대로 보존 (코드 위치 이동 시 주석 동반 이동 규칙).
- `RefreshTokenStore` port 시그니처는 변경하지 않는다 (도메인 예외는 unchecked이라 선언 불필요).

### 3. 변경 — `AuthTokenIssueService`의 인프라 장애 try-catch 제거

`src/main/java/com/commerce/auth/application/AuthTokenIssueService.java`

- `refreshTokenStore.save(...)` 주변의 try-catch 블록 **전부 제거**.
- `org.springframework.dao.DataAccessException` import 제거.
- 클래스 내 다른 로그 사용처가 없으면 `@Slf4j`와 `lombok.extern.slf4j.Slf4j` import도 제거.

결과 형태:

```java
public AuthTokenIssueResult issue(Member member) {
    TokenClaims claims = TokenClaims.of(member.getId(), member.getRole());

    String accessToken = tokenIssuer.createAccessToken(claims);
    String refreshToken = tokenIssuer.createRefreshToken(claims);

    refreshTokenStore.save(member.getId(), refreshToken);

    return AuthTokenIssueResult.of(accessToken, refreshToken);
}
```

`RefreshTokenStoreUnavailableException`은 자연 propagate되어 `AuthExceptionHandler`에서 매핑된다.

### 4. 변경 — `AuthTokenReissueService`의 인프라 장애 try-catch 제거

`src/main/java/com/commerce/auth/application/AuthTokenReissueService.java`

- `validateStoredRefreshToken` 메서드 안의 `try { stored = refreshTokenStore.get(memberId); } catch (DataAccessException e) { ... }` 전체를 제거하고, 직접 `Optional` 처리로 단순화.
- `org.springframework.dao.DataAccessException` import 제거.
- 클래스 내 다른 로그 사용처가 없으면 `@Slf4j`와 import도 제거.

결과 형태:

```java
private void validateStoredRefreshToken(Long memberId, String refreshToken) {
    String storedRefreshToken = refreshTokenStore.get(memberId)
        .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

    if (!storedRefreshToken.equals(refreshToken)) {
        throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }
}
```

`AuthException(REFRESH_TOKEN_NOT_FOUND)`는 기존 비즈니스 분기 그대로 유지한다. `RefreshTokenStoreUnavailableException`은 catch하지 않고 propagate.

### 5. 신규 — `AuthExceptionHandler` (`@RestControllerAdvice`)

`src/main/java/com/commerce/auth/exception/AuthExceptionHandler.java`

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

제약:
- 패키지는 `com.commerce.auth.exception` — auth 모듈 안에 둬 `common` → `auth` 역의존을 회피한다 (ADR-3).
- 로그를 찍지 않는다. infra adapter가 이미 ERROR + stack으로 동일 사실을 기록하므로 중복 회피 (ADR-2).
- `basePackages`나 `assignableTypes`로 범위를 좁히지 않는다. 현재 처리 대상이 `RefreshTokenStoreUnavailableException` 한 종류라 다른 컨트롤러에 도달할 일이 없다.

### 6. 신규 — `RedisRefreshTokenStoreTest`

`src/test/java/com/commerce/auth/infrastructure/RedisRefreshTokenStoreTest.java`

Mockito + `StringRedisTemplate`/`ValueOperations` mock. Order adapter 테스트 (`RedisOrderIdempotencyStoreTest`)와 동일 구조. 케이스:

- `save` 정상: `valueOperations.set(eq("refresh:1"), eq("refresh-token"), any(Duration.class))`가 호출되는지 검증.
- `save` 시 `QueryTimeoutException` → `RefreshTokenStoreUnavailableException`으로 변환 + `hasCauseInstanceOf(QueryTimeoutException.class)` 검증.
- `get` 정상: 저장된 값이 `Optional`로 반환.
- `get` 시 `QueryTimeoutException` → `RefreshTokenStoreUnavailableException` 변환 + cause 검증.

`JwtProperties`는 `ReflectionTestUtils.setField` 또는 mock으로 `refreshExpiration`만 주입한다.

### 7. 신규 — `AuthExceptionHandlerTest`

`src/test/java/com/commerce/auth/exception/AuthExceptionHandlerTest.java`

`GlobalExceptionHandlerTest`와 동일 패턴: handler를 `new`해서 직접 호출하고 `ResponseEntity` 응답을 검증한다.

- `handleRefreshTokenStoreUnavailable` 호출 시 HTTP `500 INTERNAL_SERVER_ERROR` 상태 + 본문의 `code = "AUTH-500-1"` (또는 `AuthErrorCode.INTERNAL_ERROR.getCode()`) 검증.
- 핸들러에서 로그가 발생하지 않는지 `ListAppender`로 검증 (선택, `GlobalExceptionHandlerTest` 패턴 참고).

### 8. 변경 — `AuthTokenIssueServiceTest`

`src/test/java/com/commerce/auth/application/AuthTokenIssueServiceTest.java`

- `org.springframework.dao.DataAccessException`, `org.springframework.dao.QueryTimeoutException` import 제거.
- `com.commerce.auth.exception.RefreshTokenStoreUnavailableException` import 추가.
- 기존 `issue_whenRefreshTokenStoreSaveFails_throwsAuthException` 케이스를 다음 의미로 갱신:
  - 메서드명: `issue_whenRefreshTokenStoreSaveFails_propagatesStoreUnavailable`
  - DisplayName: `"refresh token 저장 실패 시 RefreshTokenStoreUnavailableException을 그대로 propagate한다"`
  - stub: `willThrow(new RefreshTokenStoreUnavailableException(new RuntimeException("boom"))).given(refreshTokenStore).save(anyLong(), anyString());`
  - 검증: `assertThatThrownBy(() -> service.issue(member)).isInstanceOf(RefreshTokenStoreUnavailableException.class);`

### 9. 변경 — `AuthTokenReissueServiceTest`

`src/test/java/com/commerce/auth/application/AuthTokenReissueServiceTest.java`

- `org.springframework.dao.QueryTimeoutException` import 제거.
- `com.commerce.auth.exception.RefreshTokenStoreUnavailableException` import 추가.
- 기존 `reissue_whenRedisGetFails_throwInternalError` 케이스를 다음 의미로 갱신:
  - 메서드명: `reissue_whenRedisGetFails_propagatesStoreUnavailable`
  - DisplayName: `"refresh token 조회 실패 시 RefreshTokenStoreUnavailableException을 그대로 propagate한다"`
  - stub: `willThrow(new RefreshTokenStoreUnavailableException(new RuntimeException("boom"))).given(refreshTokenStore).get(1L);`
  - 검증: `assertThatThrownBy(() -> service.reissue(command)).isInstanceOf(RefreshTokenStoreUnavailableException.class);`

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

추가 검증:

```bash
grep -rn "DataAccessException" src/main/java/com/commerce/auth/
```

결과는 `src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`에서만 출현해야 하며, `src/main/java/com/commerce/auth/application/` 하위에는 0건이어야 한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `RefreshTokenStoreUnavailableException`이 `RuntimeException`을 직접 상속하고 `CustomException`을 상속하지 않는다.
   - `RedisRefreshTokenStore`가 `save`/`get` 모두 `DataAccessException`을 catch하고 ERROR 로그 + 도메인 예외 throw로 끝낸다.
   - `RefreshTokenStore` port 시그니처가 변경되지 않았다.
   - `AuthTokenIssueService`, `AuthTokenReissueService`에 인프라 장애 관련 try-catch가 남아 있지 않다.
   - `AuthExceptionHandler`가 `com.commerce.auth.exception` 패키지에 있고 `@RestControllerAdvice`로 등록됐다.
   - 핸들러가 로그를 찍지 않는다.
   - 기존 응답 (500 + `AUTH-500-1`)이 그대로 보존된다.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `RefreshTokenStoreUnavailableException`이 `CustomException`을 상속하지 마라. 이유: `GlobalExceptionHandler.handleCustomException`이 자동 매핑되어 *presentation에서 도메인 예외를 명시적으로 매핑한다* 는 책임 분리가 가려진다. 또한 미래에 fallback 분기가 도입될 때 catch 의도가 우회될 위험이 있다 (ADR-1).
- `RefreshTokenStore` port 인터페이스에 도메인 예외를 `throws`로 선언하지 마라. 이유: unchecked 예외라 선언 불필요하고, port가 도메인 예외 존재를 의식하지 않는 게 추상화 보존에 유리하다.
- application 계층에 `RefreshTokenStoreUnavailableException`을 catch하는 코드를 두지 마라. 이유: presentation 매핑 정책을 application이 가로채면 *변환만 하는 보일러플레이트* 가 그대로 남고, 책임이 두 곳으로 분산된다 (ADR-2).
- `common.exception.GlobalExceptionHandler`에 `@ExceptionHandler(RefreshTokenStoreUnavailableException.class)`를 추가하지 마라. 이유: `common`이 `auth` 도메인을 import하는 역방향 의존이 신설된다 (ADR-3).
- `AuthExceptionHandler`에 로그를 추가하지 마라. 이유: infra adapter가 이미 ERROR + stack으로 동일 사실을 기록하므로 중복이 된다 (ADR-2).
- `RedisRefreshTokenStore`에서 `DataAccessException` catch 시 다른 도메인 예외 (`OrderIdempotencyStoreUnavailableException` 등)를 throw하지 마라. 이유: 도메인 격리 위반.
- `OrderIdempotencyStoreUnavailableException`과 공통 베이스 클래스를 추출하지 마라. 이유: YAGNI. 사용처가 2곳이고 application의 catch 시나리오가 도메인별로 달라 공통 catch 가치가 없다 (ADR-4).
- `AuthErrorCode`의 응답 코드와 메시지를 변경하지 마라. 이유: 본 태스크는 응답 동작 보존 리팩터링이다.
- 기존 테스트를 깨뜨리지 마라.
