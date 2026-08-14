package com.commerce.payment.legacy.naverpay.application.port.result;

import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;

import lombok.Getter;

@Getter
public class NaverPayHistoryResult {

	public enum Status {
		APPROVED,
		CANCELED,
		FAILED,
		// PG 가 이미 처리했을 가능성이 남는 이력조회 실패(결과 불명). FAILED 와 달리 UNKNOWN 보존 대상이다.
		UNKNOWN
	}

	private final Status status;
	private final String merchantPayKey;
	private final int totalPayAmount;
	private final PaymentErrorCode errorCode;
	private final String failDetail;

	private NaverPayHistoryResult(Status status, String merchantPayKey, int totalPayAmount,
		PaymentErrorCode errorCode, String failDetail) {
		this.status = status;
		this.merchantPayKey = merchantPayKey;
		this.totalPayAmount = totalPayAmount;
		this.errorCode = errorCode;
		this.failDetail = failDetail;
	}

	public static NaverPayHistoryResult approved(String merchantPayKey, int totalPayAmount) {
		return new NaverPayHistoryResult(Status.APPROVED, merchantPayKey, totalPayAmount, null, null);
	}

	public static NaverPayHistoryResult canceled() {
		return new NaverPayHistoryResult(Status.CANCELED, null, 0, null, null);
	}

	public static NaverPayHistoryResult failed(PaymentErrorCode errorCode) {
		return new NaverPayHistoryResult(Status.FAILED, null, 0, errorCode, null);
	}

	public static NaverPayHistoryResult unknown(String failDetail) {
		return new NaverPayHistoryResult(Status.UNKNOWN, null, 0, null, failDetail);
	}
}
