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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.service.request.AuthSignUpServiceRequest;
import com.commerce.auth.service.response.AuthSignUpResponse;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private PasswordHasher passwordHasher;

	@InjectMocks
	private AuthService authService;

	@DisplayName("회원 가입 시 비밀번호를 해시하고 토큰을 반환한다")
	@Test
	void signUp_whenValidRequest_hashPasswordAndReturnTokens() {
		// given
		AuthSignUpServiceRequest request = AuthSignUpServiceRequest.builder()
			.email("test@example.com")
			.password("password123")
			.username("user1")
			.build();

		given(memberRepository.existsByEmail("test@example.com")).willReturn(false);
		given(passwordHasher.hash("password123")).willReturn("hashed-password");
		given(jwtTokenProvider.createAccessToken(any())).willReturn("access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");

		// when
		AuthSignUpResponse response = authService.signUp(request);

		// then
		ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
		then(memberRepository).should().save(memberCaptor.capture());
		assertThat(memberCaptor.getValue().getPassword()).isEqualTo("hashed-password");
		assertThat(response.getAccessToken()).isEqualTo("access-token");
		assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
	}

	@DisplayName("회원 가입 시 이메일이 중복되면 예외가 발생한다")
	@Test
	void signUp_whenEmailDuplicated_throwException() {
		// given
		AuthSignUpServiceRequest request = AuthSignUpServiceRequest.builder()
			.email("test@example.com")
			.password("password123")
			.username("user1")
			.build();

		given(memberRepository.existsByEmail("test@example.com")).willReturn(true);

		// when & then
		assertThatThrownBy(() -> authService.signUp(request))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.DUPLICATE_EMAIL);
			});
	}

}
