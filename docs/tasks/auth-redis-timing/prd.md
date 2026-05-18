# 기능 PRD

## 기능명

- `auth-redis-timing`

## 배경

회원가입은 회원을 RDB에 저장한 뒤 access token / refresh token을 발급하고 refresh token을 Redis에 저장한다.
그런데 Redis는 RDB 트랜잭션에 참여하지 않으므로 회원 DB commit과 Redis 저장이 원자적으로 보장되지 않는다.
`AuthSignUpService.signUp()`이 `@Transactional`로 열린 외부 트랜잭션 안에서 `MemberRegistrationService.register()`와
`AuthTokenIssueService.issue()`를 모두 호출하기 때문에, Redis 저장이 DB commit 전에 발생한다.

또한 `refreshTokenStore.save()` / `refreshTokenStore.get()` 실패 시 발생하는 Infrastructure 예외가
Application 계층을 통과해 Presentation까지 전파되어 CLAUDE.md 규칙을 위반하고 있다.

## 목표

- 회원가입에서 DB commit 이후 Redis 저장 순서를 보장한다.
- Redis 저장/조회 실패를 Application 계층에서 도메인 예외로 변환한다.
- 미사용 코드(`RefreshTokenStore.delete()`)를 제거해 인터페이스를 실제 사용 범위로 좁힌다.

## 범위

**포함**
- `AuthSignUpService.signUp()` 트랜잭션 분리 (`NOT_SUPPORTED`)
- `AuthTokenIssueService.issue()` Redis save 실패 예외 변환 (로그인 / 회원가입 / 재발급 공통)
- `AuthTokenReissueService` Redis get 실패 예외 변환
- `RefreshTokenStore.delete()` 제거
- 관련 단위 테스트 및 통합 테스트 추가

**제외**
- Redis HA(Sentinel / Cluster) 구성
- 로그아웃 기능 구현 (`delete()` 재추가 포함)
- Redis 연결 설정 변경

## 주요 시나리오

1. 회원가입 성공 → DB commit 완료 후 Redis에 refresh token 저장
2. 회원가입 DB 실패 → Redis에 refresh token이 남지 않는다
3. 로그인/재발급 중 Redis 저장/조회 실패 → 클라이언트에 추상화된 에러 응답

## 요구사항

- 회원가입 DB commit 이전에 refresh token이 Redis에 저장되지 않는다
- 회원가입 DB commit 실패 시 Redis에 refresh token이 남지 않는다
- Redis 저장 실패 시 응답 정책이 테스트로 고정된다
- Redis 조회 실패 시 응답 정책이 테스트로 고정된다
- Redis 장애 정보가 클라이언트 응답에 노출되지 않는다

## 제약사항

- Redis 저장/조회 실패 정책: strict (예외 처리). Redis를 인증 필수 인프라로 본다.
- 에러 코드는 Redis 장애를 암시하지 않는 추상화된 코드를 사용한다.
- 로그인 / 재발급은 DB 쓰기 트랜잭션이 없으므로 트랜잭션 분리 대상이 아니다.
