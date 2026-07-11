package com.commerce.auth.application.port;

/**
 * refresh 토큰을 검증하고 회원 id를 반환하는 port. 재발급 흐름이 쓴다.
 * refresh 토큰에는 role이 실려도 재발급이 쓰지 않으므로(회원을 다시 조회) 회원 id만 반환한다.
 */
public interface RefreshTokenValidator {

	Long validateRefreshToken(String refreshToken);
}
