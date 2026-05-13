package com.commerce.payment.naverpay.infrastructure.client.response.body;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayCancelBody {

	private String paymentId;
	private String payHistId;
	private String cancelYmdt;
	private int totalRestAmount;
}
