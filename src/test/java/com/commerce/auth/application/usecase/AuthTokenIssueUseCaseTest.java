package com.commerce.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.port.TokenIssuer;
import com.commerce.auth.application.port.vo.TokenClaims;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.infrastructure.RefreshTokenStoreUnavailableException;
import com.commerce.member.domain.Member;

@ExtendWith(MockitoExtension.class)
class AuthTokenIssueUseCaseTest {

	@Mock
	private TokenIssuer tokenIssuer;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@InjectMocks
	private AuthTokenIssueUseCase authTokenIssueService;

	@DisplayName("회원 정보로 access token과 refresh token을 발급하고 refresh token을 저장한다")
	@Test
	void issue_whenMemberProvided_issueTokensAndStoreRefreshToken() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(tokenIssuer.createAccessToken(any(TokenClaims.class))).willReturn("access-token");
		given(tokenIssuer.createRefreshToken(any(TokenClaims.class))).willReturn("refresh-token");

		// when
		AuthTokenIssueResult result = authTokenIssueService.issue(member);

		// then
		assertThat(result.getAccessToken()).isEqualTo("access-token");
		assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
		then(refreshTokenStore).should().save(1L, "refresh-token");
	}

	@DisplayName("토큰 발급 시 회원 id와 role로 JWT claims를 생성한다")
	@Test
	void issue_whenMemberProvided_createClaimsWithMemberIdAndRole() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		ArgumentCaptor<TokenClaims> accessClaimsCaptor = ArgumentCaptor.forClass(TokenClaims.class);
		ArgumentCaptor<TokenClaims> refreshClaimsCaptor = ArgumentCaptor.forClass(TokenClaims.class);

		// when
		authTokenIssueService.issue(member);

		// then
		then(tokenIssuer).should().createAccessToken(accessClaimsCaptor.capture());
		then(tokenIssuer).should().createRefreshToken(refreshClaimsCaptor.capture());
		assertThat(accessClaimsCaptor.getValue().memberId()).isEqualTo(1L);
		assertThat(accessClaimsCaptor.getValue().memberRole()).isEqualTo(member.getRole());
		assertThat(refreshClaimsCaptor.getValue().memberId()).isEqualTo(1L);
		assertThat(refreshClaimsCaptor.getValue().memberRole()).isEqualTo(member.getRole());
	}

	@DisplayName("refresh token 저장 실패 시 RefreshTokenStoreUnavailableException을 그대로 propagate한다")
	@Test
	void issue_whenRefreshTokenStoreSaveFails_propagatesStoreUnavailable() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");
		ReflectionTestUtils.setField(member, "id", 1L);

		given(tokenIssuer.createAccessToken(any(TokenClaims.class))).willReturn("access-token");
		given(tokenIssuer.createRefreshToken(any(TokenClaims.class))).willReturn("refresh-token");
		willThrow(new RefreshTokenStoreUnavailableException(new RuntimeException("boom"))).given(refreshTokenStore).save(anyLong(), anyString());

		// when & then
		assertThatThrownBy(() -> authTokenIssueService.issue(member))
			.isInstanceOf(RefreshTokenStoreUnavailableException.class);
	}
}
