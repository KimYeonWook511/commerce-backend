# Member DDD Migration Retrospective

## 배경

이번 작업은 기존 `member` persistence 구조와 `auth`에 섞여 있던 회원 생성/조회 책임을 DDD 기준으로 분리했다.

`/auth/signup`, `/auth/login`, `/auth/reissue` API 경로와 DB 계약은 바꾸지 않았다. 다만 회원 생성, 이메일 중복 검증, 회원 조회는 `member.application` 책임으로 이동하고, 인증 토큰 발급과 refresh token 관리는 `auth`에 남겼다.

## 이번 작업에서 확정한 기준

### auth API와 member 유스케이스 책임은 분리한다

- auth controller와 API 경로는 인증 API로 유지한다.
- 회원 등록은 `MemberRegistrationService`가 담당한다.
- 회원 조회는 `MemberQueryService`가 담당한다.
- auth signup/login/reissue 흐름은 `AuthSignUpService`, `AuthLoginService`, `AuthTokenReissueService`로 나누고,
  비밀번호 해싱/검증, JWT 발급/검증, refresh token 저장 흐름을 조율한다.

### repository 경계는 adapter로 분리한다

- application 계층은 `member.domain.repository.MemberRepository`에 의존한다.
- Spring Data JPA repository는 `member.infrastructure.JpaMemberRepository`에 둔다.
- domain repository 구현은 `MemberRepositoryAdapter`가 담당한다.
- 테스트 fixture나 JPA repository 자체 테스트에서는 infrastructure repository를 직접 사용할 수 있다.

### 회원 도메인 오류 코드를 사용한다

- 이메일 중복은 `MemberErrorCode.DUPLICATE_EMAIL`로 전환했다.
- 따라서 회원가입 중복 이메일 실패 응답 코드는 `MEMBER-409`를 사용한다.

## 남겨둔 legacy 참조

- ✅ `member.repository.MemberRepository` 삭제 완료
- ✅ legacy `order.service.OrderService` 삭제 완료
- ✅ 테스트 fixture에서 `MemberRepository` 참조를 `JpaMemberRepository`로 교체 완료

## 별도 이슈로 분리한 항목

- 회원가입에서 회원 저장 RDB commit과 refresh token Redis 저장은 원자적으로 보장되지 않는다.
- 이번 PR에서는 member DDD 구조 전환 범위를 유지하고, Redis 저장 시점과 실패 정책 변경은 별도 이슈에서 처리한다.
- auth service 테스트는 분리된 service 책임을 검증하는 수준으로 정리했다.
- Redis 저장 인자, RDB commit 이후 저장 정책, Redis 실패 응답 정책은 별도 이슈에서 함께 테스트로 고정한다.

## 다음 legacy 삭제 작업 체크리스트

✅ 완료

- `member.repository.MemberRepository` 삭제
- 테스트 fixture에서 legacy repository 참조를 `JpaMemberRepository`로 교체
- 전체 테스트 통과 확인

## 다음 DDD 작업에 적용할 원칙

- API 경로가 auth에 남더라도 회원 생성/조회 책임은 member application에 둔다.
- 인증 토큰, refresh token, 비밀번호 검증은 auth 책임으로 유지한다.
- legacy 삭제는 DDD 구조 도입 커밋과 계속 분리한다.
