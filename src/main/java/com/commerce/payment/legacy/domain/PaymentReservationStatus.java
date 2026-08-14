package com.commerce.payment.legacy.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentReservationStatus {
	RESERVED("예약"),
	USED("사용 완료"),
	EXPIRED("만료");

	private final String description;
}
