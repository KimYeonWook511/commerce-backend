package com.commerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.jwt.JwtTokenType;
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthTokenReissueCommand;
import com.commerce.auth.service.result.AuthTokenReissueResult;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@ExtendWith(MockitoExtension.class)
class AuthTokenReissueServiceTest {

	@Mock
	private MemberQueryService memberQueryService;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private JwtTokenValidator jwtTokenValidator;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

	@InjectMocks
	private AuthTokenReissueService authTokenReissueService;

	@DisplayName("리프레시 토큰이 유효하면 토큰을 재발급한다")
	@Test
	void reissue_whenRefreshTokenValid_returnTokens() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		Claims claims = refreshTokenClaims("1");

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("refresh-token"));
		given(memberQueryService.findById(1L)).willReturn(Optional.of(member));
		given(jwtTokenProvider.createAccessToken(any())).willReturn("new-access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("new-refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when
		AuthTokenReissueResult result = authTokenReissueService.reissue(command);

		// then
		assertThat(result.getAccessToken()).isEqualTo("new-access-token");
		assertThat(result.getRefreshToken()).isEqualTo("new-refresh-token");
		then(refreshTokenStore).should().save(any(Long.class), any(String.class), any());
	}

	@DisplayName("리프레시 토큰이 일치하지 않으면 예외가 발생한다")
	@Test
	void reissue_whenRefreshTokenMismatch_throwException() {
		// given
		Claims claims = refreshTokenClaims("1");

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("other-token"));

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueService.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
			});
	}

	@DisplayName("리프레시 토큰 타입이 아니면 예외가 발생한다")
	@Test
	void reissue_whenTokenTypeIsNotRefreshToken_throwException() {
		// given
		Claims claims = Jwts.claims();
		claims.setSubject("1");
		claims.put("type", JwtTokenType.ACCESS_TOKEN.name());

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueService.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	@DisplayName("리프레시 토큰 subject가 회원 id 형식이 아니면 예외가 발생한다")
	@Test
	void reissue_whenSubjectIsNotMemberId_throwException() {
		// given
		Claims claims = refreshTokenClaims("invalid-member-id");

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueService.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	@DisplayName("저장된 리프레시 토큰이 없으면 예외가 발생한다")
	@Test
	void reissue_whenStoredRefreshTokenNotFound_throwException() {
		// given
		Claims claims = refreshTokenClaims("1");

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);
		given(refreshTokenStore.get(1L)).willReturn(Optional.empty());

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueService.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
			});
	}

	@DisplayName("토큰의 회원이 존재하지 않으면 예외가 발생한다")
	@Test
	void reissue_whenMemberNotFound_throwException() {
		// given
		Claims claims = refreshTokenClaims("1");

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("refresh-token"));
		given(memberQueryService.findById(1L)).willReturn(Optional.empty());

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueService.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	private Claims refreshTokenClaims(String subject) {
		Claims claims = Jwts.claims();
		claims.setSubject(subject);
		claims.put("type", JwtTokenType.REFRESH_TOKEN.name());
		return claims;
	}
}
