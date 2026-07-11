package com.commerce.auth.infrastructure.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.application.port.vo.TokenClaims;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.member.domain.MemberRole;

import io.jsonwebtoken.Jwts;

class JwtRefreshTokenValidatorTest {

	private final JwtProperties jwtProperties = jwtProperties();
	private final JwtClaimsReader jwtClaimsReader = new JwtClaimsReader();
	private final JwtTokenIssuer jwtTokenIssuer = new JwtTokenIssuer(jwtProperties);
	private final JwtRefreshTokenValidator jwtRefreshTokenValidator =
		new JwtRefreshTokenValidator(jwtProperties, jwtClaimsReader);

	@DisplayName("유효한 refresh token이면 회원 id를 반환한다")
	@Test
	void validateRefreshToken_whenRefreshTokenValid_returnMemberId() {
		// given
		String token = jwtTokenIssuer.createRefreshToken(TokenClaims.of(1L, MemberRole.ROLE_USER));

		// when
		Long memberId = jwtRefreshTokenValidator.validateRefreshToken(token);

		// then
		assertThat(memberId).isEqualTo(1L);
	}

	@DisplayName("refresh 검증 시 access token 타입이면 예외가 발생한다")
	@Test
	void validateRefreshToken_whenTokenTypeIsAccessToken_throwException() {
		// given: REFRESH 서명키로 서명하되 type=ACCESS_TOKEN인 토큰 (Port로 생성 불가 → 직접 빌드)
		String token = Jwts.builder()
			.setSubject("1")
			.claim("type", JwtType.ACCESS_TOKEN.name())
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(604800000L)))
			.signWith(jwtProperties.getRefreshSecretKey())
			.compact();

		// when & then
		assertThatThrownBy(() -> jwtRefreshTokenValidator.validateRefreshToken(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception ->
				assertThat(((AuthException) exception).getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID));
	}

	@DisplayName("refresh token subject가 회원 id 형식이 아니면 예외가 발생한다")
	@Test
	void validateRefreshToken_whenSubjectInvalid_throwException() {
		// given
		String token = Jwts.builder()
			.setSubject("not-a-number")
			.claim("type", JwtType.REFRESH_TOKEN.name())
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(604800000L)))
			.signWith(jwtProperties.getRefreshSecretKey())
			.compact();

		// when & then
		assertThatThrownBy(() -> jwtRefreshTokenValidator.validateRefreshToken(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception ->
				assertThat(((AuthException) exception).getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID));
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
