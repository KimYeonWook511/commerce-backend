package com.commerce.payment.naverpay.client.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NaverPayCancelRequest {

	private String paymentId;
	private int cancelAmount;
	private String cancelReason;
	private String cancelRequester;
	private int taxScopeAmount;
	private int taxExScopeAmount;
}
