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
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.member.domain.MemberRole;

import io.jsonwebtoken.Jwts;

class JwtTokenAuthenticatorTest {

	private final JwtProperties jwtProperties = jwtProperties();
	private final JwtClaimsReader jwtClaimsReader = new JwtClaimsReader();
	private final JwtTokenIssuer jwtTokenIssuer = new JwtTokenIssuer(jwtProperties);
	private final JwtTokenAuthenticator jwtTokenAuthenticator =
		new JwtTokenAuthenticator(jwtProperties, jwtClaimsReader);

	@DisplayName("유효한 access token이면 인증 컨텍스트를 반환한다")
	@Test
	void authenticate_whenAccessTokenValid_returnContext() {
		// given
		String token = jwtTokenIssuer.createAccessToken(TokenClaims.of(1L, MemberRole.ROLE_USER));

		// when
		AuthenticationContext context = jwtTokenAuthenticator.authenticate(token);

		// then
		assertThat(context.memberId()).isEqualTo(1L);
		assertThat(context.role()).isEqualTo(Role.ROLE_USER);
	}

	@DisplayName("access 검증 시 refresh token 타입이면 예외가 발생한다")
	@Test
	void authenticate_whenTokenTypeIsRefreshToken_throwException() {
		// given: ACCESS 서명키로 서명하되 type=REFRESH_TOKEN인 토큰 (Port로 생성 불가 → 직접 빌드)
		String token = Jwts.builder()
			.setSubject("1")
			.claim("type", JwtType.REFRESH_TOKEN.name())
			.claim("role", MemberRole.ROLE_USER.name())
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(1800000L)))
			.signWith(jwtProperties.getAccessSecretKey())
			.compact();

		// when & then
		assertThatThrownBy(() -> jwtTokenAuthenticator.authenticate(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception ->
				assertThat(((AuthException) exception).getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID));
	}

	@DisplayName("access token subject가 회원 id 형식이 아니면 예외가 발생한다")
	@Test
	void authenticate_whenSubjectInvalid_throwException() {
		// given
		String token = accessToken("not-a-number", MemberRole.ROLE_USER.name());

		// when & then
		assertThatThrownBy(() -> jwtTokenAuthenticator.authenticate(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception ->
				assertThat(((AuthException) exception).getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID));
	}

	@DisplayName("access token role이 알 수 없는 값이면 예외가 발생한다")
	@Test
	void authenticate_whenRoleUnknown_throwException() {
		// given
		String token = accessToken("1", "ROLE_UNKNOWN");

		// when & then
		assertThatThrownBy(() -> jwtTokenAuthenticator.authenticate(token))
			.isInstanceOf(AuthException.class)
			.satisfies(exception ->
				assertThat(((AuthException) exception).getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID));
	}

	private String accessToken(String subject, String role) {
		return Jwts.builder()
			.setSubject(subject)
			.claim("type", JwtType.ACCESS_TOKEN.name())
			.claim("role", role)
			.setIssuedAt(Date.from(Instant.now()))
			.setExpiration(Date.from(Instant.now().plusMillis(1800000L)))
			.signWith(jwtProperties.getAccessSecretKey())
			.compact();
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
