package com.commerce.payment.naverpay.infrastructure.client.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NaverPayApprovalType {
	ALL("전체"),
	APPROVAL("승인"),
	CANCEL("취소");

	private final String description;
}
