package com.commerce.payment.application.port.result;

import com.commerce.payment.domain.PaymentAttemptFailCode;

public record CancelOutcome(Status status, PaymentAttemptFailCode failCode, String failDetail) {

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

	public static CancelOutcome failed(PaymentAttemptFailCode failCode, String failDetail) {
		return new CancelOutcome(Status.FAILED, failCode, failDetail);
	}
}
