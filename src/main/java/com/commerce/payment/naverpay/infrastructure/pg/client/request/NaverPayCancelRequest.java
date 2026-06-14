package com.commerce.payment.naverpay.infrastructure.pg.client.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NaverPayCancelRequest {

	private String paymentId;
	private int cancelAmount;
	private String cancelReason;
	private NaverPayCancelRequester cancelRequester;
	private int taxScopeAmount;
	private int taxExScopeAmount;
}
