package com.commerce.auth.presentation.http;

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

import com.commerce.auth.presentation.http.request.AuthLoginRequest;
import com.commerce.auth.presentation.http.request.AuthSignUpRequest;
import com.commerce.auth.presentation.http.request.AuthTokenReissueRequest;
import com.commerce.auth.infrastructure.jwt.JwtProperties;
import com.commerce.auth.application.AuthLoginService;
import com.commerce.auth.application.AuthSignUpService;
import com.commerce.auth.application.AuthTokenReissueService;
import com.commerce.auth.application.command.AuthLoginCommand;
import com.commerce.auth.application.command.AuthSignUpCommand;
import com.commerce.auth.application.command.AuthTokenReissueCommand;
import com.commerce.auth.application.result.AuthLoginResult;
import com.commerce.auth.application.result.AuthSignUpResult;
import com.commerce.auth.application.result.AuthTokenReissueResult;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CustomException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

	private final AuthSignUpService authSignUpService;
	private final AuthLoginService authLoginService;
	private final AuthTokenReissueService authTokenReissueService;
	private final JwtProperties jwtProperties;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<AuthSignUpResult>> signUp(
		@Valid @RequestBody AuthSignUpRequest request
	) {
		AuthSignUpResult signUpResult = authSignUpService.signUp(
			AuthSignUpCommand.builder()
				.email(request.getEmail())
				.password(request.getPassword())
				.username(request.getUsername())
				.build()
		);

		HttpHeaders headers = buildAuthHeaders(signUpResult.getAccessToken(), signUpResult.getRefreshToken());

		return ResponseEntity.status(HttpStatus.CREATED)
			.headers(headers)
			.body(ApiResponse.of(signUpResult));
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AuthLoginResult>> login(
		@Valid @RequestBody AuthLoginRequest request
	) {
		AuthLoginResult loginResult = authLoginService.login(
			AuthLoginCommand.builder()
				.email(request.getEmail())
				.password(request.getPassword())
				.build()
		);

		HttpHeaders headers = buildAuthHeaders(loginResult.getAccessToken(), loginResult.getRefreshToken());

		return ResponseEntity.status(HttpStatus.OK)
			.headers(headers)
			.body(ApiResponse.of(loginResult));
	}

	@PostMapping("/reissue")
	public ResponseEntity<ApiResponse<AuthTokenReissueResult>> reissue(
		@CookieValue(name = "refreshToken", required = false) String refreshToken,
		@RequestBody(required = false) AuthTokenReissueRequest request
	) {
		String resolvedRefreshToken = resolveRefreshToken(refreshToken, request);
		AuthTokenReissueResult reissueResult = authTokenReissueService.reissue(
			AuthTokenReissueCommand.builder()
				.refreshToken(resolvedRefreshToken)
				.build()
		);

		HttpHeaders headers = buildAuthHeaders(reissueResult.getAccessToken(), reissueResult.getRefreshToken());

		return ResponseEntity.status(HttpStatus.OK)
			.headers(headers)
			.body(ApiResponse.of(reissueResult));
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
