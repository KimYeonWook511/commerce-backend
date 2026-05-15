# Step 1: redis-save-exception

## 읽어야 할 파일

- `docs/features/auth-redis-timing/prd.md`
- `docs/features/auth-redis-timing/architecture.md`
- `docs/features/auth-redis-timing/adr.md`
- `docs/commit-conventions.md`
- `src/main/java/com/commerce/auth/application/AuthTokenIssueService.java`
- `src/main/java/com/commerce/auth/application/port/RefreshTokenStore.java`
- `src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`
- `src/main/java/com/commerce/auth/exception/AuthErrorCode.java`
- `src/main/java/com/commerce/auth/exception/AuthException.java`
- `src/test/java/com/commerce/auth/application/AuthTokenIssueServiceTest.java`
- `src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java` (DataAccessException catch 패턴 참고)

## 작업

`refreshTokenStore.save()` 실패 시 Infrastructure 예외를 Application 계층에서 도메인 예외로 변환한다.
이 변경으로 `AuthTokenIssueService`를 호출하는 로그인(`AuthLoginService`) / 회원가입 / 재발급이 모두 커버된다.

**`AuthErrorCode`** — Redis 장애를 암시하지 않는 에러 코드 추가:
```java
INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH-500", "인증 처리 중 오류가 발생했습니다"),
```

**`AuthTokenIssueService`** — `@Slf4j` 추가 + Redis save 실패 처리:
- `refreshTokenStore.save()` 호출 부분만 `try`로 감싼다
- `DataAccessException` catch → `log.error` (memberId 포함) → `AuthException(AuthErrorCode.INTERNAL_ERROR)` throw
- `DataAccessException`은 `org.springframework.dao.DataAccessException`을 사용한다 (기존 `RedisOrderIdempotencyStore` 패턴 동일)

**`AuthTokenIssueServiceTest`** — 기존 테스트 유지 + 아래 케이스 추가:
- `refreshTokenStore.save()` 가 `DataAccessException`을 던질 때 `AuthException(INTERNAL_ERROR)`이 발생한다
- BDDMockito `willThrow` 사용, `@DisplayName` / 메서드명 네이밍은 기존 테스트 컨벤션 따름

## 수정 가능 경로

- `src/main/java/com/commerce/auth/exception/AuthErrorCode.java`
- `src/main/java/com/commerce/auth/application/AuthTokenIssueService.java`
- `src/test/java/com/commerce/auth/application/AuthTokenIssueServiceTest.java`
- `docs/features/auth-redis-timing/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드 실행 후 전체 테스트 통과 확인
2. `AuthTokenIssueServiceTest`에 Redis save 실패 케이스가 추가됐는지 확인
3. `AuthErrorCode.INTERNAL_ERROR`가 추가됐는지 확인

## 금지사항

- `Exception`으로 catch하지 마라. 이유: 의도치 않은 예외까지 삼킬 수 있다. `DataAccessException`으로 한정한다.
- `AuthException`을 catch 블록 안에서 다시 잡지 마라. 이유: `DataAccessException`과 `AuthException`은 타입이 다르므로 분리 불필요.
- 기존 테스트를 깨뜨리지 마라.

## 커밋 메시지

```
fix: refresh token Redis 저장 실패를 도메인 예외로 변환한다
```
