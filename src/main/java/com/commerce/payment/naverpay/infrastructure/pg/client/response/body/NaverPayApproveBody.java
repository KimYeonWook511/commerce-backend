package com.commerce.payment.naverpay.infrastructure.pg.client.response.body;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayApproveBody {

	private String paymentId;
	private Detail detail;

	@Getter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Detail {
		private String paymentId;
		private String payHistId;
		private String merchantPayKey;
		private String admissionState;
		private int totalPayAmount;
		private String admissionYmdt;
	}
}
