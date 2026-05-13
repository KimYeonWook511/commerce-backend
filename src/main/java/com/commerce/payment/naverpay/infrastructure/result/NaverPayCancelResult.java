package com.commerce.payment.naverpay.infrastructure.result;

import com.commerce.payment.domain.PaymentAttemptFailCode;

import lombok.Getter;

@Getter
public class NaverPayCancelResult {

	public enum Status {
		SUCCESS,
		PROCESSING,
		ALREADY_CANCELED,
		FAILED
	}

	private final Status status;
	private final PaymentAttemptFailCode failCode;
	private final String failDetail;

	private NaverPayCancelResult(Status status, PaymentAttemptFailCode failCode, String failDetail) {
		this.status = status;
		this.failCode = failCode;
		this.failDetail = failDetail;
	}

	public static NaverPayCancelResult success() {
		return new NaverPayCancelResult(Status.SUCCESS, null, null);
	}

	public static NaverPayCancelResult processing() {
		return new NaverPayCancelResult(Status.PROCESSING, null, null);
	}

	public static NaverPayCancelResult alreadyCanceled() {
		return new NaverPayCancelResult(Status.ALREADY_CANCELED, null, null);
	}

	public static NaverPayCancelResult failed(PaymentAttemptFailCode failCode, String failDetail) {
		return new NaverPayCancelResult(Status.FAILED, failCode, failDetail);
	}
}
