package com.commerce.payment.infrastructure.pg.naverpay.client.response.body;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

/** 승인 응답 본문 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApproveBody {

	private Detail detail;

	@Getter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Detail {

		private String paymentId;
		private String payHistId;
		private String merchantPayKey;
		private String admissionState;
		private String admissionYmdt;
		private int totalPayAmount;
	}
}
