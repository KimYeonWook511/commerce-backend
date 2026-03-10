package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentAttemptStatus {
	REQUESTED("요청"),
	SUCCEEDED("성공"),
	FAILED("실패");

	private final String description;
}
