package com.commerce.payment.naverpay.infrastructure.client.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NaverPayCancelRequester {
	CANCEL_BY_USER("1"),
	CANCEL_BY_ADMIN("2");

	private final String code;
}
