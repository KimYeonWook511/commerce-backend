package com.commerce.payment.application.port.result;

import com.commerce.payment.domain.PaymentFailCode;

public record CancelOutcome(Status status, PaymentFailCode failCode, String failDetail) {

	public enum Status {
		SUCCESS,
		PROCESSING,
		FAILED
	}

	public static CancelOutcome success() {
		return new CancelOutcome(Status.SUCCESS, null, null);
	}

	public static CancelOutcome processing() {
		return new CancelOutcome(Status.PROCESSING, null, null);
	}

	public static CancelOutcome failed(PaymentFailCode failCode, String failDetail) {
		return new CancelOutcome(Status.FAILED, failCode, failDetail);
	}
}
