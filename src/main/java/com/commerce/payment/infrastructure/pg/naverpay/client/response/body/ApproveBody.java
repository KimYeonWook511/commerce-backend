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
		/** 결제창을 열 때 실어 보낸 우리 회원 키가 그대로 돌아온다 */
		private String merchantUserKey;
		private String admissionState;
		private String admissionYmdt;
		private int totalPayAmount;
	}
}
