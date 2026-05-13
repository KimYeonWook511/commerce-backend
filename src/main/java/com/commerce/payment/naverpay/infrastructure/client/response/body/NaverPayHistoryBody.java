package com.commerce.payment.naverpay.infrastructure.client.response.body;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayHistoryBody {

	private List<History> list;
	private int totalCount;
	private int responseCount;
	private int totalPageCount;
	private int currentPageNumber;

	@Getter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class History {
		private String paymentId;
		private String admissionState;
		private String admissionTypeCode;
		private int totalPayAmount;
		private String merchantPayKey;

		public boolean isCompletedApproval() {
			return "SUCCESS".equalsIgnoreCase(this.admissionState)
				&& "01".equals(this.admissionTypeCode);
		}

		public boolean isCanceledApproval() {
			return ("CANCELLED".equalsIgnoreCase(this.admissionState)
				|| "CANCELED".equalsIgnoreCase(this.admissionState))
				&& "03".equals(this.admissionTypeCode);
		}
	}
}
