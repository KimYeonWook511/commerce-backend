package com.commerce.auth.controller;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.auth.controller.request.AuthLoginRequest;
import com.commerce.auth.controller.request.AuthSignUpRequest;
import com.commerce.auth.controller.request.AuthTokenReissueRequest;
import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.service.AuthService;
import com.commerce.auth.service.request.AuthTokenReissueServiceRequest;
import com.commerce.auth.service.response.AuthLoginResponse;
import com.commerce.auth.service.response.AuthSignUpResponse;
import com.commerce.auth.service.response.AuthTokenReissueResponse;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CustomException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;
	private final JwtProperties jwtProperties;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<AuthSignUpResponse>> signUp(
		@Valid @RequestBody AuthSignUpRequest request
	) {
		AuthSignUpResponse signUpResponse = authService.signUp(request.toServiceRequest());

		HttpHeaders headers = buildAuthHeaders(signUpResponse.getAccessToken(), signUpResponse.getRefreshToken());

		return ResponseEntity.status(HttpStatus.CREATED)
			.headers(headers)
			.body(ApiResponse.of(signUpResponse));
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AuthLoginResponse>> login(
		@Valid @RequestBody AuthLoginRequest request
	) {
		AuthLoginResponse loginResponse = authService.login(request.toServiceRequest());

		HttpHeaders headers = buildAuthHeaders(loginResponse.getAccessToken(), loginResponse.getRefreshToken());

		return ResponseEntity.status(HttpStatus.OK)
			.headers(headers)
			.body(ApiResponse.of(loginResponse));
	}

	@PostMapping("/reissue")
	public ResponseEntity<ApiResponse<AuthTokenReissueResponse>> reissue(
		@CookieValue(name = "refreshToken", required = false) String refreshToken,
		@RequestBody(required = false) AuthTokenReissueRequest request
	) {
		String resolvedRefreshToken = resolveRefreshToken(refreshToken, request);
		AuthTokenReissueResponse reissueResponse = authService.reissue(
			AuthTokenReissueServiceRequest.builder()
				.refreshToken(resolvedRefreshToken)
				.build()
		);

		HttpHeaders headers = buildAuthHeaders(reissueResponse.getAccessToken(), reissueResponse.getRefreshToken());

		return ResponseEntity.status(HttpStatus.OK)
			.headers(headers)
			.body(ApiResponse.of(reissueResponse));
	}

	private HttpHeaders buildAuthHeaders(String accessToken, String refreshToken) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(accessToken);
		headers.add(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(refreshToken).toString());
		return headers;
	}

	private ResponseCookie createRefreshTokenCookie(String refreshToken) {
		Duration maxAge = Duration.ofMillis(jwtProperties.getRefreshExpiration());

		return ResponseCookie.from("refreshToken", refreshToken)
			.httpOnly(true)
			.secure(false)
			.path("/")
			.maxAge(maxAge)
			.sameSite("Strict")
			.build();
	}

	private String resolveRefreshToken(String cookieRefreshToken, AuthTokenReissueRequest request) {
		if (StringUtils.hasText(cookieRefreshToken)) {
			return cookieRefreshToken;
		}

		if (request != null && StringUtils.hasText(request.getRefreshToken())) {
			return request.getRefreshToken();
		}

		throw new CustomException(CommonErrorCode.INVALID_REQUEST);
	}

}
