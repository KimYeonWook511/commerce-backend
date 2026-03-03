package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentAttemptStatus {
	APPROVE_REQUESTED("승인 요청"),
	APPROVE_SUCCEEDED("승인 성공"),
	APPROVE_FAILED("승인 실패"),
	CANCEL_REQUESTED("취소 요청"),
	CANCEL_SUCCEEDED("취소 성공"),
	CANCEL_FAILED("취소 실패");

	private final String description;
}
