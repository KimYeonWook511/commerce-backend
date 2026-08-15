package com.commerce.payment.infrastructure.pg.naverpay.client.request;

import com.commerce.payment.domain.RefundRequester;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 우리 요청자 값을 결제사 값으로 옮기는 표. 결제사 필수 필드라 빠지면 요청 자체가 거절된다.
 *
 * <p>옮기는 일을 어댑터가 갖는다 — 도메인은 자기 enum만 알고, 결제사가 늘면 그 결제사의 표를 새로 만든다.
 */
@Getter
@RequiredArgsConstructor
public enum CancelRequester {

	BY_BUYER("1"),
	BY_MERCHANT_ADMIN("2");

	private final String code;

	public static CancelRequester from(RefundRequester requester) {
		return requester == RefundRequester.MEMBER ? BY_BUYER : BY_MERCHANT_ADMIN;
	}
}
