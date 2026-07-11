# 인증/인가 진입점은 common.security leaf가 소유하고, 토큰 인증만 port로 역전한다

- Status: accepted
- Date: 2026-07-11

## Context

인증/인가 진입점(필터·인가 인터셉터·인증 컨텍스트·argument resolver)이 top-level `security` 패키지에 있었고, 이 패키지가 auth·member 도메인을 역참조했다. 그 결과 `common.config.WebConfig`가 security를 등록 → security가 auth를 호출 → auth가 common 예외를 상속하는 **`common → security → auth → common` 3-패키지 사이클**이 실재했다. 게다가 `@RequireRole`·인증 컨텍스트는 모든 도메인 컨트롤러가 의존하는데, 그런 "많은 것이 의존하는" 패키지일수록 leaf(아무것도 의존 안 함)여야 안정적이다(안정 의존성 원칙).

## Decision

- security 전체를 `com.commerce.common.security`로 옮겨 **leaf**로 둔다. 인증/인가를 웹 진입점에 강제하는 authz shared kernel이며, 자기 웹 구성을 스스로 등록하고, 어떤 도메인·auth·토큰 기술(JWT)도 의존하지 않는다. 이동으로 위 사이클이 소멸한다(모두 이미 common을 의존하므로 새 inter-package 엣지가 0).
- "토큰 → 누구인가"라는 **단 하나의 호출만 port로 역전**한다. `TokenAuthenticator`(port, common.security)를 auth가 JWT로 구현한다(`JwtTokenAuthenticator`, auth → common 한 방향). security는 토큰 기술을 모른다.
- 인가 어휘 **`Role`은 authz leaf(common.security)가 소유**한다. 도메인의 회원 분류(`member.MemberRole`)는 그대로 두고, 둘은 JWT의 role 문자열로만 오간다(발급 시 `name()`, 검증 시 `valueOf`). member 도메인은 순수하게 유지되고, 두 enum은 서로를 직접 참조하지 않는다.
- 필터가 스스로 판정하는 실패(토큰 없음·권한 부족)는 `SecurityErrorCode`가 낸다. code/message는 인증/인가 문제 공간의 계약(AUTH-401/403)을 그대로 쓴다 — 클래스가 security에 있는 것은 leaf를 지키기 위한 정의 위치일 뿐, 코드는 AUTH 문제 공간에 속한다. 토큰 만료·무효 등 "토큰을 검증해야 아는" 코드는 auth의 `AuthException`이 port 호출로 전파되며, 필터가 공통 베이스 `CustomException`으로 받아 코드를 그대로 내보낸다.
- ArchUnit이 "common은 leaf(도메인·auth 무의존)"를 강제한다.

## Consequences

- **얻는 것**: 3-패키지 사이클이 사라지고, 가장 많이 의존받는 authz 진입점이 leaf가 되어 안정적이다. security를 읽는 사람은 토큰 기술을 몰라도 되고, JWT 세부는 auth.infrastructure.jwt에만 남는다.
- **보존**: DB 무변경(`Role`은 `@Enumerated(STRING)`·저장값 동일). api-spec 무변경(AUTH-401/403·401-2/401-3 코드 문자열 보존). 외부 동작 변화 0.
- **감수**: member 도메인이 `common.security.Role`을 의존하는 방향이 생긴다. 다만 `Role`은 동작·프레임워크 의존이 없는 순수 enum이라 실질 오염이 아니고, 대안(enum 2벌 중복)보다 단순하다.
- **정리**: 이동을 계기로 토큰 읽기 경로의 군더더기를 함께 걷어냈다 — access/refresh 어댑터를 분리해 port↔adapter 이름을 짝맞추고(`TokenAuthenticator`↔`JwtTokenAuthenticator`, `RefreshTokenValidator`↔`JwtRefreshTokenValidator`), 공통 파싱을 `JwtClaimsReader`로 통합해 얇은 래퍼(`TokenAuthenticationUseCase`)·죽은 필드(`ParsedTokenClaims`)·중복 파싱을 제거했다.
