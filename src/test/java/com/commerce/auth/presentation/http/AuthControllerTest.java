package com.commerce.auth.presentation.http;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.application.service.AuthLoginService;
import com.commerce.auth.application.usecase.AuthSignUpUseCase;
import com.commerce.auth.application.service.AuthTokenReissueService;
import com.commerce.auth.application.usecase.TokenAuthenticationUseCase;
import com.commerce.auth.application.dto.AuthLoginCommand;
import com.commerce.auth.application.dto.AuthSignUpCommand;
import com.commerce.auth.application.dto.AuthLoginResult;
import com.commerce.auth.application.dto.AuthSignUpResult;
import com.commerce.auth.infrastructure.jwt.JwtProperties;
import com.commerce.member.domain.Member;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthSignUpUseCase authSignUpService;

	@MockitoBean
	private AuthLoginService authLoginService;

	@MockitoBean
	private AuthTokenReissueService authTokenReissueService;

	@MockitoBean
	private JwtProperties jwtProperties;

	@MockitoBean
	private TokenAuthenticationUseCase tokenAuthenticationService;

	@DisplayName("회원가입 성공 시 member 필드와 토큰을 반환한다")
	@Test
	void signUp_whenValidRequest_returnMemberAndTokens() throws Exception {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "tester");
		ReflectionTestUtils.setField(member, "id", 1L);
		given(authSignUpService.signUp(org.mockito.ArgumentMatchers.any(AuthSignUpCommand.class)))
			.willReturn(AuthSignUpResult.from(member, "access-token", "refresh-token"));
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		// when & then
		mockMvc.perform(post("/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "test@example.com",
					  "password": "password123",
					  "username": "tester"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
			.andExpect(cookie().value("refreshToken", "refresh-token"))
			.andExpect(jsonPath("$.data.member.memberId").value(1L))
			.andExpect(jsonPath("$.data.member.email").value("test@example.com"))
			.andExpect(jsonPath("$.data.member.username").value("tester"))
			.andExpect(jsonPath("$.data.memberDetailResult").doesNotExist())
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
	}

	@DisplayName("로그인 성공 시 member 필드와 토큰을 반환한다")
	@Test
	void login_whenValidRequest_returnMemberAndTokens() throws Exception {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "tester");
		ReflectionTestUtils.setField(member, "id", 1L);
		given(authLoginService.login(org.mockito.ArgumentMatchers.any(AuthLoginCommand.class)))
			.willReturn(AuthLoginResult.from(member, "access-token", "refresh-token"));
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		// when & then
		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "test@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
			.andExpect(cookie().value("refreshToken", "refresh-token"))
			.andExpect(jsonPath("$.data.member.memberId").value(1L))
			.andExpect(jsonPath("$.data.member.email").value("test@example.com"))
			.andExpect(jsonPath("$.data.member.username").value("tester"))
			.andExpect(jsonPath("$.data.memberDetailResult").doesNotExist())
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
	}
}
