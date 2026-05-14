package com.commerce.auth.infrastructure.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;

import com.commerce.auth.application.port.vo.ParsedTokenClaims;
import com.commerce.auth.application.port.vo.TokenClaims;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.member.domain.MemberRole;

import io.jsonwebtoken.Jwts;

class JwtTokenValidatorTest {

	@DisplayName("access token이 유효하면 claims를 반환한다")
	@Test
	void validateAccessToken_whenAccessTokenValid_returnClaims() {
		// given
		JwtProperties jwtProperties = jwtProperties();
		JwtTokenIssuer jwtTokenIssuer = new JwtTokenIssuer(jwtProperties);
		JwtTokenValidator jwtTokenValidator = new JwtTokenValidator(jwtProperties);
		String token = jwtTokenIssuer.createAccessToken(TokenClaims.of(1L, MemberRole.ROLE_USER));

		// when
		ParsedTokenClaims claims = jwtTokenValidator.validateAccessToken(token);

		// then
		assertThat(claims.subject()).isEqualTo("1");
	}

	@DisplayName("access token 검증 시 refresh token 타입이면 예외가 발생한다")
	@Test
	void validateAccessToken_whenTokenTypeIsRefreshToken_throwException() {
		// given: ACCESS 서명키로 서명하되 type=REFRESH_TOKEN인 토큰 (Port로 생성 불가 → 직접 빌드)
		JwtProperties jwtProperties = jwtProperties();
		JwtTokenValidator jwtTokenValidator = new JwtTokenValidator(jwtProperties);
		String token = Jwts.builder()
			.setSubject("1")
			.claim("type", JwtType.REFRESH_TOKEN.name())
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(1800000L)))
			.signWith(jwtProperties.getAccessSecretKey())
			.compact();

		// when & then
		assertThatThrownBy(() -> jwtTokenValidator.validateAccessToken(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	@DisplayName("refresh token 검증 시 access token 타입이면 예외가 발생한다")
	@Test
	void validateRefreshToken_whenTokenTypeIsAccessToken_throwException() {
		// given: REFRESH 서명키로 서명하되 type=ACCESS_TOKEN인 토큰 (Port로 생성 불가 → 직접 빌드)
		JwtProperties jwtProperties = jwtProperties();
		JwtTokenValidator jwtTokenValidator = new JwtTokenValidator(jwtProperties);
		String token = Jwts.builder()
			.setSubject("1")
			.claim("type", JwtType.ACCESS_TOKEN.name())
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(604800000L)))
			.signWith(jwtProperties.getRefreshSecretKey())
			.compact();

		// when & then
		assertThatThrownBy(() -> jwtTokenValidator.validateRefreshToken(token))
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
