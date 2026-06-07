package com.commerce.payment.naverpay.application.port.result;

import com.commerce.payment.domain.PaymentFailCode;

import lombok.Getter;

@Getter
public class NaverPayCancelResult {

	public enum Status {
		SUCCESS,
		PROCESSING,
		ALREADY_CANCELED,
		FAILED,
		// PG 가 취소를 처리했는지 불명(네트워크/서버오류/응답 해석 불가). FAILED 와 달리 UNKNOWN 보존 대상이다.
		UNKNOWN
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

	public static NaverPayCancelResult unknown(String failDetail) {
		return new NaverPayCancelResult(Status.UNKNOWN, null, failDetail);
	}
}
