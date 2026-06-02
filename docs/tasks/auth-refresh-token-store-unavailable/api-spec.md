# 태스크 API 스펙

## 개요

API 응답 규약 변경 없음. Auth 도메인의 Redis 장애 처리 패턴을 *application 직접 catch* → *infra 매핑 + presentation 응답 매핑* 으로 전환하는 내부 리팩터링이다. 응답 코드, 에러 코드, 메시지 모두 기존과 동일하다.

## 엔드포인트

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/auth/tokens` (또는 로그인/회원가입 흐름 중 토큰 발급 경로) | refresh token 발급 시 Redis 장애 발생 시 동작 |
| POST | `/api/auth/tokens/reissue` | refresh token 재발급 시 Redis 조회 장애 발생 시 동작 |

실제 엔드포인트 경로는 `AuthController` 정의 그대로 유지. 본 태스크는 응답 규약 변경 없음.

## 요청

기존과 동일.

## 응답

### 200 OK — 정상 발급/재발급

기존과 동일.

### 400 / 401 / 404 — 검증 실패, 토큰 불일치, 회원 미존재 등

기존과 동일.

### 500 Internal Server Error — `AuthErrorCode.INTERNAL_ERROR`

Redis 장애로 refresh token 저장/조회가 실패한 경우. **응답 자체는 기존과 동일** 하다.

- 기존: application이 `DataAccessException`을 catch → `log.error` + `AuthException(INTERNAL_ERROR)` throw → `GlobalExceptionHandler.handleCustomException` → 500.
- 변경 후: infra adapter (`RedisRefreshTokenStore`)가 `DataAccessException`을 catch → `log.error` + `RefreshTokenStoreUnavailableException` throw. application은 catch하지 않음. presentation의 `AuthExceptionHandler` (`@RestControllerAdvice`)가 `RefreshTokenStoreUnavailableException`을 받아 `AUTH-500-1` 500 응답으로 매핑.

응답 본문, 에러 코드 (`AUTH-500-1`), 메시지 모두 변경 없음.

## 검증 규칙

응답 검증 규칙 변경 없음.

## 비고

- **운영 로그 위치 변경**: Redis 장애 시 ERROR + stack 로그가 *infra adapter* (`RedisRefreshTokenStore`)에서 발생한다. 기존에는 *application* (`AuthTokenIssueService`, `AuthTokenReissueService`)에서 발생했다. 모니터링 alert가 클래스 이름 기반이라면 alert 룰을 함께 갱신해야 한다.
- **로그 중복 제거**: application의 `log.error`와 presentation의 ERROR 로그가 모두 사라지고, 같은 장애에 대한 ERROR 로그가 infra adapter에서 한 번만 남는다.
- **응답 매핑 책임 위치 변경**: 응답 500 `AUTH-500-1` 매핑이 `common.exception.GlobalExceptionHandler.handleCustomException` → `auth.exception.AuthExceptionHandler.handleRefreshTokenStoreUnavailable`로 이동한다. 응답 결과는 동일하지만 매핑 진입점이 다르다.
