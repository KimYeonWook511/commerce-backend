# 기능 아키텍처

## 개요

이번 변경은 `auth` 도메인의 Application 계층 두 곳을 수정한다.
API 추가, DB 스키마 변경, 새로운 인프라 컴포넌트 도입은 없다.

## 변경 대상

| 파일 | 변경 내용 |
|---|---|
| `AuthSignUpService` | `@Transactional` → `@Transactional(propagation = NOT_SUPPORTED)` |
| `AuthTokenIssueService` | Redis save 실패 → `DataAccessException` catch → `AuthException(INTERNAL_ERROR)` |
| `AuthTokenReissueService` | Redis get 실패 → `DataAccessException` catch → `AuthException(INTERNAL_ERROR)` |
| `AuthErrorCode` | `INTERNAL_ERROR` 에러 코드 추가 |
| `RefreshTokenStore` (port) | `delete()` 제거 |
| `RedisRefreshTokenStore` (infra) | `delete()` 구현 제거 |

## 설계 방향

### 트랜잭션 분리 (`NOT_SUPPORTED`)

`AuthSignUpService.signUp()`에 `@Transactional(propagation = NOT_SUPPORTED)` 적용.

- class-level `@Transactional(readOnly = true)`를 명시적으로 override
- `signUp()`은 트랜잭션 없이 실행
- `register()`는 외부 트랜잭션이 없으므로 자체 `@Transactional`로 새 트랜잭션을 시작하고 commit 후 반환
- `issue()` 호출 시점 = DB commit 완료 이후

```
signUp() NOT_SUPPORTED
  register() REQUIRED → 자체 트랜잭션 → commit → 반환
  issue() → Redis 저장 (DB commit 이후)
```

기존 코드베이스에서 `OrderCreateService.createOrder()`가 동일 패턴을 사용한다.

### Redis 예외 변환

CLAUDE.md 원칙: "Infrastructure 예외는 Application 계층에서 도메인 예외로 변환하고 Presentation으로 넘기지 않는다."

`DataAccessException` (Spring Redis가 Redis 예외를 래핑하는 타입)을 catch하여:
1. 서버 로그에 상세 정보 기록
2. `AuthException(AuthErrorCode.INTERNAL_ERROR)` 로 변환

`AuthErrorCode.INTERNAL_ERROR`는 Redis 장애를 암시하지 않는 추상화된 코드.
클라이언트에는 "인증 처리 중 오류가 발생했습니다" 메시지만 전달.

## 데이터 흐름

**회원가입 정상 흐름**
```
Client → AuthController → AuthSignUpService.signUp() [NOT_SUPPORTED]
  → MemberRegistrationService.register() [@Transactional → commit]
  → AuthTokenIssueService.issue() [트랜잭션 없음]
      → TokenIssuer.createAccessToken()
      → TokenIssuer.createRefreshToken()
      → RefreshTokenStore.save() → Redis
  → AuthSignUpResult 반환
```

**회원가입 Redis 실패 흐름**
```
  → RefreshTokenStore.save() → DataAccessException
  → AuthTokenIssueService catch → log.error → AuthException(INTERNAL_ERROR)
  → AuthController → GlobalExceptionHandler → 500 응답
  (DB에는 member가 이미 commit됨. 다음 시도에서 DUPLICATE_EMAIL 또는 로그인 성공)
```

**재발급 Redis 조회 실패 흐름**
```
  → RefreshTokenStore.get() → DataAccessException
  → AuthTokenReissueService catch → log.error → AuthException(INTERNAL_ERROR)
  → GlobalExceptionHandler → 500 응답
```

## 예외 및 실패 처리

| 시나리오 | 처리 방식 | 클라이언트 응답 |
|---|---|---|
| 회원가입 DB 실패 | `MemberException` 전파 | 4xx (중복 이메일 등) |
| 회원가입 Redis save 실패 | `AuthException(INTERNAL_ERROR)` | 500 |
| 로그인 Redis save 실패 | `AuthException(INTERNAL_ERROR)` | 500 |
| 재발급 Redis get 실패 | `AuthException(INTERNAL_ERROR)` | 500 |
| 재발급 Redis save 실패 | `AuthException(INTERNAL_ERROR)` | 500 |

## 테스트 포인트

- `AuthTokenIssueServiceTest`: Redis save 실패 시 `AuthException(INTERNAL_ERROR)` 발생
- `AuthTokenReissueServiceTest`: Redis get 실패 시 `AuthException(INTERNAL_ERROR)` 발생
- `AuthSignUpServiceIntegrationTest`:
  - 회원가입 성공 후 Redis에 refresh token 저장 확인
  - 회원가입 실패(중복 이메일) 후 Redis에 refresh token 없음 확인
