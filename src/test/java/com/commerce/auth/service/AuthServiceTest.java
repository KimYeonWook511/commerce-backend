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
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.jwt.JwtTokenType;
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthLoginCommand;
import com.commerce.auth.service.command.AuthSignUpCommand;
import com.commerce.auth.service.command.AuthTokenReissueCommand;
import com.commerce.auth.service.result.AuthLoginResult;
import com.commerce.auth.service.result.AuthSignUpResult;
import com.commerce.auth.service.result.AuthTokenReissueResult;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private JwtTokenValidator jwtTokenValidator;

	@Mock
	private PasswordHasher passwordHasher;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

	@InjectMocks
	private AuthService authService;

	@DisplayName("회원 가입 시 비밀번호를 해시하고 토큰을 반환한다")
	@Test
	void signUp_whenValidRequest_hashPasswordAndReturnTokens() {
		// given
		AuthSignUpCommand command = AuthSignUpCommand.builder()
			.email("test@example.com")
			.password("password123")
			.username("user1")
			.build();

		given(memberRepository.existsByEmail("test@example.com")).willReturn(false);
		given(memberRepository.save(any(Member.class))).willAnswer(invocation -> {
			Member saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 1L);
			return saved;
		});
		given(passwordHasher.hash("password123")).willReturn("hashed-password");
		given(jwtTokenProvider.createAccessToken(any())).willReturn("access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		// when
		AuthSignUpResult result = authService.signUp(command);

		// then
		ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
		then(memberRepository).should().save(memberCaptor.capture());
		then(refreshTokenStore).should().save(any(Long.class), any(String.class), any());
		assertThat(memberCaptor.getValue().getPassword()).isEqualTo("hashed-password");
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
	}

	@DisplayName("회원 가입 시 이메일이 중복되면 예외가 발생한다")
	@Test
	void signUp_whenEmailDuplicated_throwException() {
		// given
		AuthSignUpCommand command = AuthSignUpCommand.builder()
			.email("test@example.com")
			.password("password123")
			.username("user1")
			.build();

		given(memberRepository.existsByEmail("test@example.com")).willReturn(true);

		// when & then
		assertThatThrownBy(() -> authService.signUp(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.DUPLICATE_EMAIL);
			});
	}

	@DisplayName("로그인 시 비밀번호가 일치하면 토큰을 반환한다")
	@Test
	void login_whenPasswordMatches_returnTokens() {
		// given
		Member member = Member.builder()
			.email("test@example.com")
			.password("hashed-password")
			.username("user1")
			.build();
		ReflectionTestUtils.setField(member, "id", 1L);

		given(memberRepository.findByEmail("test@example.com")).willReturn(Optional.of(member));
		given(passwordHasher.matches("password123", "hashed-password")).willReturn(true);
		given(jwtTokenProvider.createAccessToken(any())).willReturn("access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		AuthLoginCommand command = AuthLoginCommand.builder()
			.email("test@example.com")
			.password("password123")
			.build();

		// when
		AuthLoginResult result = authService.login(command);

		// then
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
		then(refreshTokenStore).should().save(any(Long.class), any(String.class), any());
	}

	@DisplayName("로그인 시 비밀번호가 불일치하면 예외가 발생한다")
	@Test
	void login_whenPasswordDoesNotMatch_throwException() {
		// given
		Member member = Member.builder()
			.email("test@example.com")
			.password("hashed-password")
			.username("user1")
			.build();
		ReflectionTestUtils.setField(member, "id", 1L);

		given(memberRepository.findByEmail("test@example.com")).willReturn(Optional.of(member));
		given(passwordHasher.matches("password123", "hashed-password")).willReturn(false);

		AuthLoginCommand command = AuthLoginCommand.builder()
			.email("test@example.com")
			.password("password123")
			.build();

		// when & then
		assertThatThrownBy(() -> authService.login(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
			});
	}

	@DisplayName("리프레시 토큰이 유효하면 토큰을 재발급한다")
	@Test
	void reissue_whenRefreshTokenValid_returnTokens() {
		// given
		Member member = Member.builder()
			.email("test@example.com")
			.password("hashed-password")
			.username("user1")
			.build();
		ReflectionTestUtils.setField(member, "id", 1L);

		Claims claims = Jwts.claims();
		claims.setSubject("1");
		claims.put("type", JwtTokenType.REFRESH_TOKEN.name());

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("refresh-token"));
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(jwtTokenProvider.createAccessToken(any())).willReturn("new-access-token");
		given(jwtTokenProvider.createRefreshToken(any())).willReturn("new-refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when
		AuthTokenReissueResult result = authService.reissue(command);

		// then
		assertThat(result.getAccessToken()).isEqualTo("new-access-token");
		assertThat(result.getRefreshToken()).isEqualTo("new-refresh-token");
		then(refreshTokenStore).should().save(any(Long.class), any(String.class), any());
	}

	@DisplayName("리프레시 토큰이 일치하지 않으면 예외가 발생한다")
	@Test
	void reissue_whenRefreshTokenMismatch_throwException() {
		// given
		Claims claims = Jwts.claims();
		claims.setSubject("1");
		claims.put("type", JwtTokenType.REFRESH_TOKEN.name());

		given(jwtTokenValidator.validateRefreshToken("refresh-token")).willReturn(claims);
		given(refreshTokenStore.get(1L)).willReturn(Optional.of("other-token"));

		AuthTokenReissueCommand command = AuthTokenReissueCommand.builder()
			.refreshToken("refresh-token")
			.build();

		// when & then
		assertThatThrownBy(() -> authService.reissue(command))
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> {
				AuthException authException = (AuthException) exception;
				assertThat(authException.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
			});
	}

}
