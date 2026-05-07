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
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthLoginCommand;
import com.commerce.auth.service.result.AuthLoginResult;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthLoginServiceTest {

	@Mock
	private MemberQueryService memberQueryService;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private PasswordHasher passwordHasher;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

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
		given(jwtTokenProvider.createAccessToken(any())).willReturn("access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		AuthLoginCommand command = AuthLoginCommand.builder()
			.email("test@example.com")
			.password("password123")
			.build();

		// when
		AuthLoginResult result = authLoginService.login(command);

		// then
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
		then(refreshTokenStore).should().save(any(Long.class), any(String.class), any());
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
	}
}
