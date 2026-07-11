package com.commerce.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.application.dto.AuthTokenReissueCommand;
import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.port.RefreshTokenValidator;
import com.commerce.auth.application.dto.AuthTokenIssueResult;
import com.commerce.auth.application.dto.AuthTokenReissueResult;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.auth.infrastructure.RefreshTokenStoreUnavailableException;
import com.commerce.member.application.service.FindMemberService;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthTokenReissueUseCaseTest {

	@Mock
	private FindMemberService findMemberService;

	@Mock
	private AuthTokenIssueUseCase authTokenIssueUseCase;

	@Mock
	private RefreshTokenValidator refreshTokenValidator;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@InjectMocks
	private AuthTokenReissueUseCase authTokenReissueUseCase;

	@DisplayName("리프레시 토큰이 유효하면 토큰을 재발급한다")
	@Test
	void reissue_whenRefreshTokenValid_returnTokens() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(refreshTokenValidator.validateRefreshToken("refresh-token")).willReturn(1L);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("refresh-token"));
		given(findMemberService.findById(1L)).willReturn(Optional.of(member));
		given(authTokenIssueUseCase.issue(member))
			.willReturn(AuthTokenIssueResult.of("new-access-token", "new-refresh-token"));

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when
		AuthTokenReissueResult result = authTokenReissueUseCase.reissue(command);

		// then
		assertThat(result.getAccessToken()).isEqualTo("new-access-token");
		assertThat(result.getRefreshToken()).isEqualTo("new-refresh-token");
		then(authTokenIssueUseCase).should().issue(member);
	}

	@DisplayName("리프레시 토큰이 일치하지 않으면 예외가 발생한다")
	@Test
	void reissue_whenRefreshTokenMismatch_throwException() {
		// given
		given(refreshTokenValidator.validateRefreshToken("refresh-token")).willReturn(1L);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("other-token"));

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueUseCase.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
			});
	}

	@DisplayName("리프레시 토큰 검증에 실패하면 예외가 발생한다")
	@Test
	void reissue_whenRefreshTokenInvalid_throwException() {
		// given
		given(refreshTokenValidator.validateRefreshToken("refresh-token"))
			.willThrow(new AuthException(AuthErrorCode.TOKEN_INVALID));

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueUseCase.reissue(command))
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
		given(refreshTokenValidator.validateRefreshToken("refresh-token")).willReturn(1L);
		given(refreshTokenStore.get(1L)).willReturn(Optional.empty());

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueUseCase.reissue(command))
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
		given(refreshTokenValidator.validateRefreshToken("refresh-token")).willReturn(1L);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("refresh-token"));
		given(findMemberService.findById(1L)).willReturn(Optional.empty());

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueUseCase.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.TOKEN_INVALID);
			});
	}

	@DisplayName("refresh token 조회 실패 시 RefreshTokenStoreUnavailableException을 그대로 propagate한다")
	@Test
	void reissue_whenRedisGetFails_propagatesStoreUnavailable() {
		// given
		given(refreshTokenValidator.validateRefreshToken("refresh-token")).willReturn(1L);
		willThrow(new RefreshTokenStoreUnavailableException(new RuntimeException("boom"))).given(refreshTokenStore).get(1L);

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authTokenReissueUseCase.reissue(command))
			.isInstanceOf(RefreshTokenStoreUnavailableException.class);
	}
}
