package com.commerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthSignUpCommand;
import com.commerce.auth.service.result.AuthSignUpResult;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.application.MemberRegistrationService;
import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthSignUpServiceTest {

	@Mock
	private MemberRegistrationService memberRegistrationService;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private PasswordHasher passwordHasher;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

	@InjectMocks
	private AuthSignUpService authSignUpService;

	@DisplayName("회원가입 시 비밀번호를 해시하고 회원 등록 후 토큰을 반환한다")
	@Test
	void signUp_whenValidRequest_hashPasswordRegisterMemberAndReturnTokens() {
		// given
		AuthSignUpCommand command = AuthSignUpCommand.builder()
			.email("test@example.com")
			.password("password123")
			.username("user1")
			.build();

		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(passwordHasher.hash("password123")).willReturn("hashed-password");
		given(memberRegistrationService.register(any(MemberRegistrationCommand.class))).willReturn(member);
		given(jwtTokenProvider.createAccessToken(any())).willReturn("access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		// when
		AuthSignUpResult result = authSignUpService.signUp(command);

		// then
		ArgumentCaptor<MemberRegistrationCommand> commandCaptor = ArgumentCaptor.forClass(MemberRegistrationCommand.class);
		then(memberRegistrationService).should().register(commandCaptor.capture());
		then(refreshTokenStore).should().save(any(Long.class), any(String.class), any());
		assertThat(commandCaptor.getValue().getEmail()).isEqualTo("test@example.com");
		assertThat(commandCaptor.getValue().getPasswordHash()).isEqualTo("hashed-password");
		assertThat(commandCaptor.getValue().getUsername()).isEqualTo("user1");
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
	}
}
