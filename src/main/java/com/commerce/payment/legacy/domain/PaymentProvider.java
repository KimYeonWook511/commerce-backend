package com.commerce.payment.legacy.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentProvider {
	NAVERPAY("네이버페이"),
	KAKAOPAY("카카오페이");

	private final String value;
}
