package com.commerce.payment.legacy.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentType {
	APPROVE("승인"),
	CANCEL("취소");

	private final String description;
}
