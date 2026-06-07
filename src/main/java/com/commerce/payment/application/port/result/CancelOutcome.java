package com.commerce.payment.application.port.result;

import com.commerce.payment.domain.PaymentFailCode;

public record CancelOutcome(Status status, PaymentFailCode failCode, String failDetail) {

	public enum Status {
		SUCCESS,
		PROCESSING,
		FAILED,
		// PG 가 취소를 처리했는지 불명. cancel 기록을 UNKNOWN 으로 보존해 대사 대상으로 남긴다.
		UNKNOWN
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

	public static CancelOutcome unknown(String failDetail) {
		return new CancelOutcome(Status.UNKNOWN, null, failDetail);
	}
}
