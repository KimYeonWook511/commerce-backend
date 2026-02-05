package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
	PENDING("결제 준비됨(승인 전)"),
	PROCESSING("승인 처리 중"),
	COMPLETED("승인 완료"),
	FAILED("결제 실패"),
	CANCEL_PENDING("취소 요청 중"),
	CANCELED("취소 완료");

	private final String description;
}
