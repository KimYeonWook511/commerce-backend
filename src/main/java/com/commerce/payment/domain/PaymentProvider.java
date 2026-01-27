package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentProvider {
	NAVERPAY("네이버페이");

	private final String value;
}
