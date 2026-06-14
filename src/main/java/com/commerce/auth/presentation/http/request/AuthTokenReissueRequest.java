package com.commerce.auth.presentation.http.request;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthTokenReissueRequest {

	private String refreshToken;

}
