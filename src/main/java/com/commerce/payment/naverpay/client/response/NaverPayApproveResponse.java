package com.commerce.payment.naverpay.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayApproveResponse {

	private String code;
	private String message;
	private Body body;

	@Getter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Body {
		private String paymentId;
		private Detail detail;
	}

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
