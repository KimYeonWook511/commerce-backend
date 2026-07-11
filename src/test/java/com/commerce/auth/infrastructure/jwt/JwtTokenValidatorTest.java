package com.commerce.auth.infrastructure.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.application.port.vo.TokenClaims;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.member.domain.MemberRole;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtTokenValidatorTest {

	private static final SecretKey WRONG_KEY =
		Keys.hmacShaKeyFor("completelyDifferentWrongKey0123456789abcdef!!".getBytes());

	private final JwtProperties jwtProperties = jwtProperties();
	private final JwtTokenIssuer jwtTokenIssuer = new JwtTokenIssuer(jwtProperties);
	private final JwtTokenValidator jwtTokenValidator = new JwtTokenValidator(jwtProperties);

	// ── access (TokenValidator) ──

	@DisplayName("유효한 access token이면 인증 컨텍스트를 반환한다")
	@Test
	void validate_whenAccessTokenValid_returnContext() {
		String token = jwtTokenIssuer.createAccessToken(TokenClaims.of(1L, MemberRole.ROLE_USER));

		AuthenticationContext context = jwtTokenValidator.validate(token);

		assertThat(context.memberId()).isEqualTo(1L);
		assertThat(context.role()).isEqualTo(Role.ROLE_USER);
	}

	@DisplayName("access 검증 시 refresh token 타입이면 예외가 발생한다")
	@Test
	void validate_whenTokenTypeIsRefreshToken_throwException() {
		String token = signedToken("1", JwtType.REFRESH_TOKEN, "ROLE_USER", jwtProperties.getAccessSecretKey());
		assertAuthErrorCode(() -> jwtTokenValidator.validate(token), AuthErrorCode.TOKEN_INVALID);
	}

	@DisplayName("access token subject가 회원 id 형식이 아니면 예외가 발생한다")
	@Test
	void validate_whenSubjectInvalid_throwException() {
		String token = signedToken("not-a-number", JwtType.ACCESS_TOKEN, "ROLE_USER", jwtProperties.getAccessSecretKey());
		assertAuthErrorCode(() -> jwtTokenValidator.validate(token), AuthErrorCode.TOKEN_INVALID);
	}

	@DisplayName("access token role이 알 수 없는 값이면 예외가 발생한다")
	@Test
	void validate_whenRoleUnknown_throwException() {
		String token = signedToken("1", JwtType.ACCESS_TOKEN, "ROLE_UNKNOWN", jwtProperties.getAccessSecretKey());
		assertAuthErrorCode(() -> jwtTokenValidator.validate(token), AuthErrorCode.TOKEN_INVALID);
	}

	@DisplayName("access token 서명이 위조되면(다른 키로 서명) TOKEN_INVALID 예외가 발생한다")
	@Test
	void validate_whenSignatureTampered_throwException() {
		String token = signedToken("1", JwtType.ACCESS_TOKEN, "ROLE_USER", WRONG_KEY);
		assertAuthErrorCode(() -> jwtTokenValidator.validate(token), AuthErrorCode.TOKEN_INVALID);
	}

	// ── refresh (RefreshTokenValidator) ──

	@DisplayName("유효한 refresh token이면 회원 id를 반환한다")
	@Test
	void validateRefreshToken_whenRefreshTokenValid_returnMemberId() {
		String token = jwtTokenIssuer.createRefreshToken(TokenClaims.of(1L, MemberRole.ROLE_USER));

		assertThat(jwtTokenValidator.validateRefreshToken(token)).isEqualTo(1L);
	}

	@DisplayName("refresh 검증 시 access token 타입이면 예외가 발생한다")
	@Test
	void validateRefreshToken_whenTokenTypeIsAccessToken_throwException() {
		String token = signedToken("1", JwtType.ACCESS_TOKEN, "ROLE_USER", jwtProperties.getRefreshSecretKey());
		assertAuthErrorCode(() -> jwtTokenValidator.validateRefreshToken(token), AuthErrorCode.TOKEN_INVALID);
	}

	@DisplayName("refresh token subject가 회원 id 형식이 아니면 예외가 발생한다")
	@Test
	void validateRefreshToken_whenSubjectInvalid_throwException() {
		String token = signedToken("not-a-number", JwtType.REFRESH_TOKEN, "ROLE_USER", jwtProperties.getRefreshSecretKey());
		assertAuthErrorCode(() -> jwtTokenValidator.validateRefreshToken(token), AuthErrorCode.TOKEN_INVALID);
	}

	@DisplayName("refresh token 서명이 위조되면(다른 키로 서명) TOKEN_INVALID 예외가 발생한다")
	@Test
	void validateRefreshToken_whenSignatureTampered_throwException() {
		String token = signedToken("1", JwtType.REFRESH_TOKEN, "ROLE_USER", WRONG_KEY);
		assertAuthErrorCode(() -> jwtTokenValidator.validateRefreshToken(token), AuthErrorCode.TOKEN_INVALID);
	}

	private String signedToken(String subject, JwtType type, String role, SecretKey key) {
		return Jwts.builder()
			.setSubject(subject)
			.claim("type", type.name())
			.claim("role", role)
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(1800000L)))
			.signWith(key)
			.compact();
	}

	private void assertAuthErrorCode(ThrowingCallable callable, AuthErrorCode expected) {
		assertThatThrownBy(callable)
			.isInstanceOf(AuthException.class)
			.satisfies(e -> assertThat(((AuthException) e).getErrorCode()).isEqualTo(expected));
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
