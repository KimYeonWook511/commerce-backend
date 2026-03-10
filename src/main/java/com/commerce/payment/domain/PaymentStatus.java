package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
	COMPLETED("승인 완료"),
	CANCELED("취소 완료");

	private final String description;
}
