package com.commerce.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtTokenClaims;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.jwt.JwtTokenType;
import com.commerce.auth.service.request.AuthSignUpServiceRequest;
import com.commerce.auth.service.response.AuthSignUpResponse;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

	private final MemberRepository memberRepository;
	private final JwtTokenProvider jwtTokenProvider;
	private final PasswordHasher passwordHasher;

	@Transactional
	public AuthSignUpResponse signUp(AuthSignUpServiceRequest request) {
		// 이메일 중복
		if (memberRepository.existsByEmail(request.getEmail())) {
			throw new AuthException(AuthErrorCode.DUPLICATE_EMAIL);
		}

		Member member = Member.builder()
			.email(request.getEmail())
			.password(passwordHasher.hash(request.getPassword()))
			.username(request.getUsername())
			.build();
		memberRepository.save(member);

		// JWT 토큰 발급
		JwtTokenClaims accessTokenClaims = JwtTokenClaims.from(member, JwtTokenType.ACCESS_TOKEN);
		JwtTokenClaims refreshTokenClaims = JwtTokenClaims.from(member, JwtTokenType.REFRESH_TOKEN);
		String accessToken = jwtTokenProvider.createAccessToken(accessTokenClaims);
		String refreshToken = jwtTokenProvider.createRefreshToken(refreshTokenClaims);
		return AuthSignUpResponse.from(member, accessToken, refreshToken);
	}

}
