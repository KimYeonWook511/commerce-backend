# Auth DDD Migration Retrospective

## 배경

이번 작업은 `auth`를 엔티티 중심 도메인이 아니라 인증 bounded context로 보고 구조를 정리했다.

`/auth/signup`, `/auth/login`, `/auth/reissue` API 경로와 DB 계약은 바꾸지 않았다. 회원 생성/조회 책임은 계속 `member.application`에 두고, `auth`는 비밀번호 검증, 토큰 발급/재발급, refresh token 저장 흐름을 담당한다.

signup/login 응답의 회원 정보 필드는 구현 용어가 드러난 `memberDetailResult`에서 API 의미가 더 직접적인 `member`로 정리했다.

## 이번 작업에서 확정한 기준

### auth와 security 책임은 분리한다

- `auth.application`은 로그인, 회원가입 후 인증, 토큰 재발급 같은 인증 유스케이스를 담당한다.
- HTTP 요청에서 인증 정보를 꺼내고 controller 인자로 주입하는 흐름은 `security` 공통 패키지에 둔다.
- `JwtAuthenticationFilter`, `AuthenticatedMemberIdArgumentResolver`, `AuthorizationInterceptor`, `AuthenticationContext`는 특정 auth API가 아니라 전체 웹 요청 인증/인가를 지원하므로 `security`로 분리한다.

### JWT 구현은 auth 내부에 둔다

- JWT 생성/검증은 인증 토큰 구현 방식이므로 `auth.infrastructure.jwt`에 둔다.
- `JwtAuthenticationFilter`는 JWT validator를 직접 소유하지 않고 `TokenAuthenticationService`를 호출한다.
- `TokenAuthenticationService`는 security principal이 아니라 `TokenAuthenticationResult`를 반환한다.
- 이 기준으로 security는 HTTP adapter 역할만 하고, 토큰이 누구의 인증 정보인지 판단하는 책임은 auth application에 남긴다.
- security는 `TokenAuthenticationResult`를 request thread의 `AuthenticationContext`에 저장해 resolver와 interceptor가 사용할 수 있게 한다.

### 기술 구현은 infrastructure에 둔다

- Redis 기반 refresh token 저장소는 `auth.infrastructure.RefreshTokenStore`에 둔다.
- Spring `PasswordEncoder`를 감싼 비밀번호 해싱 구현은 `auth.infrastructure.PasswordHasher`에 둔다.
- application service는 이 구현들을 조합하되 API/DB 계약이나 토큰 정책은 변경하지 않는다.

## 남겨둔 항목

- Spring Security 도입은 이번 범위에 포함하지 않았다.
- filter whitelist, cookie `secure` 옵션, refresh token TTL, JWT claim 구조는 변경하지 않았다.
- legacy 패키지 삭제와 repository adapter 일관성 정리는 후속 작업으로 남긴다.

## 다음 작업에 적용할 원칙

- `auth`는 인증 유스케이스의 owner로 유지한다.
- `security`는 웹 요청 인증/인가 adapter로 사용하고, 특정 토큰 구현을 직접 소유하지 않는다.
- provider, legacy 삭제, adapter 일관성 정리는 별도 브랜치에서 분리해 진행한다.
