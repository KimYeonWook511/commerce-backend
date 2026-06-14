package com.commerce.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.auth.application.command.AuthLoginCommand;
import com.commerce.auth.application.result.AuthLoginResult;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.auth.application.usecase.AuthTokenIssueUseCase;
import com.commerce.member.application.service.MemberQueryService;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthLoginServiceTest {

	@Mock
	private MemberQueryService memberQueryService;

	@Mock
	private AuthTokenIssueUseCase authTokenIssueService;

	@Mock
	private PasswordHasher passwordHasher;

	@InjectMocks
	private AuthLoginService authLoginService;

	@DisplayName("로그인 시 비밀번호가 일치하면 토큰을 반환한다")
	@Test
	void login_whenPasswordMatches_returnTokens() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(memberQueryService.findByEmail("test@example.com")).willReturn(Optional.of(member));
		given(passwordHasher.matches("password123", "hashed-password")).willReturn(true);
		given(authTokenIssueService.issue(member)).willReturn(AuthTokenIssueResult.of("access-token", "refresh-token"));

		AuthLoginCommand command = AuthLoginCommand.builder()
			.email("test@example.com")
			.password("password123")
			.build();

		// when
		AuthLoginResult result = authLoginService.login(command);

		// then
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
		then(authTokenIssueService).should().issue(member);
	}

	@DisplayName("로그인 시 이메일에 해당하는 회원이 없으면 예외가 발생한다")
	@Test
	void login_whenMemberNotFound_throwException() {
		// given
		given(memberQueryService.findByEmail("test@example.com")).willReturn(Optional.empty());

		AuthLoginCommand command = AuthLoginCommand.builder()
			.email("test@example.com")
			.password("password123")
			.build();

		// when & then
		assertThatThrownBy(() -> authLoginService.login(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
			});
		then(authTokenIssueService).should(never()).issue(any());
	}

	@DisplayName("로그인 시 비밀번호가 불일치하면 예외가 발생한다")
	@Test
	void login_whenPasswordDoesNotMatch_throwException() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(memberQueryService.findByEmail("test@example.com")).willReturn(Optional.of(member));
		given(passwordHasher.matches("password123", "hashed-password")).willReturn(false);

		AuthLoginCommand command = AuthLoginCommand.builder()
			.email("test@example.com")
			.password("password123")
			.build();

		// when & then
		assertThatThrownBy(() -> authLoginService.login(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
			});
		then(authTokenIssueService).should(never()).issue(any());
	}
}
