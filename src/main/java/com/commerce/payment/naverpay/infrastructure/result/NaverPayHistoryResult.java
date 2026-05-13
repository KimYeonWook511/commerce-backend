package com.commerce.payment.naverpay.infrastructure.result;

import com.commerce.payment.exception.PaymentErrorCode;

import lombok.Getter;

@Getter
public class NaverPayHistoryResult {

	public enum Status {
		APPROVED,
		CANCELED,
		FAILED
	}

	private final Status status;
	private final String merchantPayKey;
	private final int totalPayAmount;
	private final PaymentErrorCode errorCode;

	private NaverPayHistoryResult(Status status, String merchantPayKey, int totalPayAmount,
		PaymentErrorCode errorCode) {
		this.status = status;
		this.merchantPayKey = merchantPayKey;
		this.totalPayAmount = totalPayAmount;
		this.errorCode = errorCode;
	}

	public static NaverPayHistoryResult approved(String merchantPayKey, int totalPayAmount) {
		return new NaverPayHistoryResult(Status.APPROVED, merchantPayKey, totalPayAmount, null);
	}

	public static NaverPayHistoryResult canceled() {
		return new NaverPayHistoryResult(Status.CANCELED, null, 0, null);
	}

	public static NaverPayHistoryResult failed(PaymentErrorCode errorCode) {
		return new NaverPayHistoryResult(Status.FAILED, null, 0, errorCode);
	}
}
