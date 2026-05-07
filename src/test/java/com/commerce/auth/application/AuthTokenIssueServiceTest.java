package com.commerce.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.infrastructure.RefreshTokenStore;
import com.commerce.auth.infrastructure.jwt.JwtProperties;
import com.commerce.auth.infrastructure.jwt.JwtClaims;
import com.commerce.auth.infrastructure.jwt.JwtProvider;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthTokenIssueServiceTest {

	@Mock
	private JwtProvider jwtProvider;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

	@InjectMocks
	private AuthTokenIssueService authTokenIssueService;

	@DisplayName("회원 정보로 access token과 refresh token을 발급하고 refresh token을 저장한다")
	@Test
	void issue_whenMemberProvided_issueTokensAndStoreRefreshToken() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(jwtProvider.createAccessToken(any(JwtClaims.class))).willReturn("access-token");
		given(jwtProvider.createRefreshToken(any(JwtClaims.class))).willReturn("refresh-token");
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		// when
		AuthTokenIssueResult result = authTokenIssueService.issue(member);

		// then
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
		then(refreshTokenStore).should().save(1L, "refresh-token", Duration.ofMillis(604800000L));
	}

	@DisplayName("토큰 발급 시 회원 id와 role로 JWT claims를 생성한다")
	@Test
	void issue_whenMemberProvided_createClaimsWithMemberIdAndRole() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);
		given(jwtProperties.getRefreshExpiration()).willReturn(604800000L);

		ArgumentCaptor<JwtClaims> accessClaimsCaptor = ArgumentCaptor.forClass(JwtClaims.class);
		ArgumentCaptor<JwtClaims> refreshClaimsCaptor = ArgumentCaptor.forClass(JwtClaims.class);

		// when
		authTokenIssueService.issue(member);

		// then
		then(jwtProvider).should().createAccessToken(accessClaimsCaptor.capture());
		then(jwtProvider).should().createRefreshToken(refreshClaimsCaptor.capture());
		assertThat(accessClaimsCaptor.getValue().getMemberId()).isEqualTo(1L);
		assertThat(accessClaimsCaptor.getValue().getMemberRole()).isEqualTo(member.getRole());
		assertThat(refreshClaimsCaptor.getValue().getMemberId()).isEqualTo(1L);
		assertThat(refreshClaimsCaptor.getValue().getMemberRole()).isEqualTo(member.getRole());
	}
}
