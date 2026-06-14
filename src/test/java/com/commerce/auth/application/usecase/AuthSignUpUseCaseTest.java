package com.commerce.auth.application.usecase;

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

import com.commerce.auth.application.dto.AuthSignUpCommand;
import com.commerce.auth.application.dto.AuthSignUpResult;
import com.commerce.auth.application.dto.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.member.application.service.MemberRegistrationService;
import com.commerce.member.application.dto.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthSignUpUseCaseTest {

	@Mock
	private MemberRegistrationService memberRegistrationService;

	@Mock
	private AuthTokenIssueUseCase authTokenIssueService;

	@Mock
	private PasswordHasher passwordHasher;

	@InjectMocks
	private AuthSignUpUseCase authSignUpUseCase;

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
		given(authTokenIssueService.issue(member)).willReturn(AuthTokenIssueResult.of("access-token", "refresh-token"));

		// when
		AuthSignUpResult result = authSignUpUseCase.signUp(command);

		// then
		ArgumentCaptor<MemberRegistrationCommand> commandCaptor = ArgumentCaptor.forClass(MemberRegistrationCommand.class);
		then(memberRegistrationService).should().register(commandCaptor.capture());
		then(authTokenIssueService).should().issue(member);
		assertThat(commandCaptor.getValue().getEmail()).isEqualTo("test@example.com");
		assertThat(commandCaptor.getValue().getPasswordHash()).isEqualTo("hashed-password");
		assertThat(commandCaptor.getValue().getUsername()).isEqualTo("user1");
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
	}
}
