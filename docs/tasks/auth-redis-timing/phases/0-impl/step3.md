# Step 4: redis-get-exception

## 읽어야 할 파일

- `docs/features/auth-redis-timing/prd.md`
- `docs/features/auth-redis-timing/architecture.md`
- `docs/features/auth-redis-timing/adr.md`
- `docs/commit-conventions.md`
- `src/main/java/com/commerce/auth/application/AuthTokenReissueService.java`
- `src/main/java/com/commerce/auth/application/port/RefreshTokenStore.java`
- `src/main/java/com/commerce/auth/exception/AuthErrorCode.java` (step1에서 추가된 INTERNAL_ERROR 확인)
- `src/test/java/com/commerce/auth/application/AuthTokenReissueServiceTest.java`

## 작업

`refreshTokenStore.get()` 실패 시 Infrastructure 예외를 Application 계층에서 도메인 예외로 변환한다.

**`AuthTokenReissueService`** — `@Slf4j` 추가 + `validateStoredRefreshToken()` 수정:

Redis 호출 부분만 `try`로 감싸고, 비즈니스 로직(`orElseThrow`, 값 비교)은 `try` 블록 밖에 둔다.
`DataAccessException`과 `AuthException`을 같은 catch에 넣지 않도록 구조를 분리한다.

```java
private void validateStoredRefreshToken(Long memberId, String refreshToken) {
    Optional<String> stored;
    try {
        stored = refreshTokenStore.get(memberId);  // Redis 호출만 감쌈
    } catch (DataAccessException e) {
        log.error("refresh token 조회 실패: memberId={}", memberId, e);
        throw new AuthException(AuthErrorCode.INTERNAL_ERROR);
    }

    String storedRefreshToken = stored
        .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

    if (!storedRefreshToken.equals(refreshToken)) {
        throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }
}
```

**`AuthTokenReissueServiceTest`** — 기존 테스트 유지 + 아래 케이스 추가:
- `refreshTokenStore.get()` 가 `DataAccessException`을 던질 때 `AuthException(INTERNAL_ERROR)`이 발생한다
- BDDMockito `willThrow` 사용

## 수정 가능 경로

- `src/main/java/com/commerce/auth/application/AuthTokenReissueService.java`
- `src/test/java/com/commerce/auth/application/AuthTokenReissueServiceTest.java`
- `docs/features/auth-redis-timing/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 전체 테스트 통과 확인
2. `AuthTokenReissueServiceTest`에 Redis get 실패 케이스가 추가됐는지 확인
3. 기존 `REFRESH_TOKEN_NOT_FOUND` 케이스가 여전히 동작하는지 확인

## 금지사항

- `orElseThrow`를 `try` 블록 안에 넣지 마라. 이유: `AuthException`이 `DataAccessException` catch에 걸리지 않도록 분리해야 한다.
- `Exception`으로 catch하지 마라. 이유: `DataAccessException`으로 한정한다.
- 기존 테스트를 깨뜨리지 마라.

## 커밋 메시지

```
fix: refresh token Redis 조회 실패를 도메인 예외로 변환한다
```
