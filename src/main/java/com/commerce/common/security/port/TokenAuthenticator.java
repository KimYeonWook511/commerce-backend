package com.commerce.common.security.port;

import com.commerce.common.security.context.AuthenticationContext;

/**
 * "토큰 → 누구인가"를 뒤집은 port. 토큰 기술(JWT 등)을 모르는 순수 계약이며, 구현은 auth가 소유한다
 * (구현이 common을 의존하는 한 방향). 검증 실패 시 공통 {@code CustomException}(auth의 AuthException 등)을
 * 던지고, 필터가 그 예외를 공통 베이스로 받아 코드를 전파한다.
 */
public interface TokenAuthenticator {

	AuthenticationContext authenticate(String accessToken);
}
