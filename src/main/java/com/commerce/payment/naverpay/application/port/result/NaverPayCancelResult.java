package com.commerce.payment.naverpay.application.port.result;

import com.commerce.payment.domain.PaymentFailCode;

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
	private final PaymentFailCode failCode;
	private final String failDetail;

	private NaverPayCancelResult(Status status, PaymentFailCode failCode, String failDetail) {
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

	public static NaverPayCancelResult failed(PaymentFailCode failCode, String failDetail) {
		return new NaverPayCancelResult(Status.FAILED, failCode, failDetail);
	}
}
