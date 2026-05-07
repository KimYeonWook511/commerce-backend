package com.commerce.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.infrastructure.jwt.JwtValidator;
import com.commerce.auth.application.result.TokenAuthenticationResult;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@ExtendWith(MockitoExtension.class)
class TokenAuthenticationServiceTest {

	@Mock
	private JwtValidator jwtValidator;

	@InjectMocks
	private TokenAuthenticationService tokenAuthenticationService;

	@DisplayName("access token이 유효하면 인증 주체를 반환한다")
	@Test
	void authenticateAccessToken_whenTokenValid_returnPrincipal() {
		// given
		Claims claims = Jwts.claims().setSubject("1");
		claims.put("role", "ROLE_USER");
		given(jwtValidator.validateAccessToken("access-token")).willReturn(claims);

		// when
		TokenAuthenticationResult result = tokenAuthenticationService.authenticateAccessToken("access-token");

		// then
		assertThat(result.getMemberId()).isEqualTo(1L);
		assertThat(result.getRole()).isEqualTo("ROLE_USER");
	}

	@DisplayName("access token subject가 회원 id 형식이 아니면 예외가 발생한다")
	@Test
	void authenticateAccessToken_whenSubjectInvalid_throwException() {
		// given
		Claims claims = Jwts.claims().setSubject("invalid-member-id");
		claims.put("role", "ROLE_USER");
		given(jwtValidator.validateAccessToken("access-token")).willReturn(claims);

		// when & then
		assertThatThrownBy(() -> tokenAuthenticationService.authenticateAccessToken("access-token"))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}
}
