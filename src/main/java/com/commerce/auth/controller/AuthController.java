package com.commerce.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.auth.controller.request.AuthLoginRequest;
import com.commerce.auth.controller.request.AuthSignUpRequest;
import com.commerce.auth.service.AuthService;
import com.commerce.auth.service.response.AuthLoginResponse;
import com.commerce.auth.service.response.AuthSignUpResponse;
import com.commerce.common.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<AuthSignUpResponse>> signUp(
		@Valid @RequestBody AuthSignUpRequest request
	) {
		AuthSignUpResponse memberSignUpResponse = authService.signUp(request.toServiceRequest());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(memberSignUpResponse));
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AuthLoginResponse>> login(
		@Valid @RequestBody AuthLoginRequest request
	) {
		AuthLoginResponse loginResponse = authService.login(request.toServiceRequest());
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(loginResponse));
	}

}
