package com.commerce.auth.infrastructure.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.member.domain.MemberRole;

import io.jsonwebtoken.Claims;

class JwtValidatorTest {

	@DisplayName("access token이 유효하면 claims를 반환한다")
	@Test
	void validateAccessToken_whenAccessTokenValid_returnClaims() {
		// given
		JwtProperties jwtProperties = jwtProperties();
		JwtProvider jwtProvider = new JwtProvider(jwtProperties);
		JwtValidator jwtValidator = new JwtValidator(jwtProperties);
		String token = jwtProvider.createAccessToken(JwtClaims.of(1L, MemberRole.ROLE_USER, JwtType.ACCESS_TOKEN));

		// when
		Claims claims = jwtValidator.validateAccessToken(token);

		// then
		assertThat(claims.getSubject()).isEqualTo("1");
		assertThat(claims.get("type", String.class)).isEqualTo(JwtType.ACCESS_TOKEN.name());
	}

	@DisplayName("access token 검증 시 refresh token 타입이면 예외가 발생한다")
	@Test
	void validateAccessToken_whenTokenTypeIsRefreshToken_throwException() {
		// given
		JwtProperties jwtProperties = jwtProperties();
		JwtProvider jwtProvider = new JwtProvider(jwtProperties);
		JwtValidator jwtValidator = new JwtValidator(jwtProperties);
		String token = jwtProvider.createAccessToken(JwtClaims.of(1L, MemberRole.ROLE_USER, JwtType.REFRESH_TOKEN));

		// when & then
		assertThatThrownBy(() -> jwtValidator.validateAccessToken(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	@DisplayName("refresh token 검증 시 access token 타입이면 예외가 발생한다")
	@Test
	void validateRefreshToken_whenTokenTypeIsAccessToken_throwException() {
		// given
		JwtProperties jwtProperties = jwtProperties();
		JwtProvider jwtProvider = new JwtProvider(jwtProperties);
		JwtValidator jwtValidator = new JwtValidator(jwtProperties);
		String token = jwtProvider.createRefreshToken(JwtClaims.of(1L, MemberRole.ROLE_USER, JwtType.ACCESS_TOKEN));

		// when & then
		assertThatThrownBy(() -> jwtValidator.validateRefreshToken(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	private JwtProperties jwtProperties() {
		JwtProperties jwtProperties = new JwtProperties();
		ReflectionTestUtils.setField(jwtProperties, "accessSecretKeyStr", "testAccessSecretKey0123456789abcdef!!");
		ReflectionTestUtils.setField(jwtProperties, "refreshSecretKeyStr", "testRefreshSecretKey0123456789abcdef!!");
		ReflectionTestUtils.setField(jwtProperties, "accessExpiration", 1800000L);
		ReflectionTestUtils.setField(jwtProperties, "refreshExpiration", 604800000L);
		jwtProperties.init();
		return jwtProperties;
	}
}
