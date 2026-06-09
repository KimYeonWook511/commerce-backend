package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
	REQUESTED("요청"),
	SUCCEEDED("성공"),
	FAILED("실패"),
	UNKNOWN("미확인"),
	MANUAL_REVIEW("운영자 확인 필요");

	private final String description;
}
