package com.commerce.auth.service;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.jwt.JwtTokenClaims;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.jwt.JwtTokenType;
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.request.AuthLoginServiceRequest;
import com.commerce.auth.service.request.AuthSignUpServiceRequest;
import com.commerce.auth.service.request.AuthTokenReissueServiceRequest;
import com.commerce.auth.service.response.AuthLoginResponse;
import com.commerce.auth.service.response.AuthSignUpResponse;
import com.commerce.auth.service.response.AuthTokenReissueResponse;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

	private final MemberRepository memberRepository;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtTokenValidator jwtTokenValidator;
	private final PasswordHasher passwordHasher;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

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

		// 토큰 저장
		storeRefreshToken(member.getId(), refreshToken);

		return AuthSignUpResponse.from(member, accessToken, refreshToken);
	}

	public AuthLoginResponse login(AuthLoginServiceRequest request) {
		Member member = memberRepository.findByEmail(request.getEmail())
			.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

		if (!passwordHasher.matches(request.getPassword(), member.getPassword())) {
			throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
		}

		// JWT 토큰 발급
		JwtTokenClaims accessTokenClaims = JwtTokenClaims.from(member, JwtTokenType.ACCESS_TOKEN);
		JwtTokenClaims refreshTokenClaims = JwtTokenClaims.from(member, JwtTokenType.REFRESH_TOKEN);
		String accessToken = jwtTokenProvider.createAccessToken(accessTokenClaims);
		String refreshToken = jwtTokenProvider.createRefreshToken(refreshTokenClaims);

		// 토큰 저장
		storeRefreshToken(member.getId(), refreshToken);

		return AuthLoginResponse.from(member, accessToken, refreshToken);
	}

	public AuthTokenReissueResponse reissue(AuthTokenReissueServiceRequest request) {
		String refreshToken = request.getRefreshToken();
		Claims claims = jwtTokenValidator.validateRefreshToken(refreshToken);

		// 리프레시 토큰 타입 검증
		validateRefreshTokenType(claims);

		Long memberId = Long.parseLong(claims.getSubject());

		// Redis에 저장된 토큰과의 일치 여부 검증
		validateStoredRefreshToken(memberId, refreshToken);

		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.TOKEN_INVALID));

		JwtTokenClaims accessTokenClaims = JwtTokenClaims.from(member, JwtTokenType.ACCESS_TOKEN);
		JwtTokenClaims refreshTokenClaims = JwtTokenClaims.from(member, JwtTokenType.REFRESH_TOKEN);
		String newAccessToken = jwtTokenProvider.createAccessToken(accessTokenClaims);
		String newRefreshToken = jwtTokenProvider.createRefreshToken(refreshTokenClaims);

		storeRefreshToken(memberId, newRefreshToken);

		return AuthTokenReissueResponse.of(newAccessToken, newRefreshToken);
	}

	private void storeRefreshToken(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		refreshTokenStore.save(memberId, refreshToken, ttl);
	}

	private void validateRefreshTokenType(Claims claims) {
		String tokenType = claims.get("type", String.class);

		if (!JwtTokenType.REFRESH_TOKEN.name().equals(tokenType)) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}

	private void validateStoredRefreshToken(Long memberId, String refreshToken) {
		String storedRefreshToken = refreshTokenStore.get(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

		if (!storedRefreshToken.equals(refreshToken)) {
			throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
		}
	}

}
