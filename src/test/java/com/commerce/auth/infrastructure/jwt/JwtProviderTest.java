package com.commerce.auth.infrastructure.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.MemberRole;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

class JwtProviderTest {

	@DisplayName("JWT 만료 시간 설정값을 밀리초 단위로 반영한다")
	@Test
	void createAccessToken_whenExpirationConfigured_applyExpirationAsMillis() {
		// given
		JwtProperties jwtProperties = jwtProperties(2000L, 604800000L);
		JwtProvider jwtProvider = new JwtProvider(jwtProperties);
		JwtClaims jwtClaims = JwtClaims.of(1L, MemberRole.ROLE_USER, JwtType.ACCESS_TOKEN);

		// when
		String token = jwtProvider.createAccessToken(jwtClaims);

		// then
		Claims claims = Jwts.parserBuilder()
			.setSigningKey(jwtProperties.getAccessSecretKey())
			.build()
			.parseClaimsJws(token)
			.getBody();

		long ttlMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
		assertThat(ttlMillis).isEqualTo(2000L);
	}

	private JwtProperties jwtProperties(Long accessExpiration, Long refreshExpiration) {
		JwtProperties jwtProperties = new JwtProperties();
		ReflectionTestUtils.setField(jwtProperties, "accessSecretKeyStr", "testAccessSecretKey0123456789abcdef!!");
		ReflectionTestUtils.setField(jwtProperties, "refreshSecretKeyStr", "testRefreshSecretKey0123456789abcdef!!");
		ReflectionTestUtils.setField(jwtProperties, "accessExpiration", accessExpiration);
		ReflectionTestUtils.setField(jwtProperties, "refreshExpiration", refreshExpiration);
		jwtProperties.init();
		return jwtProperties;
	}
}
