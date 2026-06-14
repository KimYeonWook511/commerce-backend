package com.commerce.payment.naverpay.application.port.result;

import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.exception.PaymentErrorCode;

import lombok.Getter;

@Getter
public class NaverPayApproveResult {

	public enum Status {
		SUCCESS,
		PROCESSING,
		ALREADY_COMPLETE,
		FAILED,
		// PG 호출 timeout / 네트워크 단절로 결과를 알 수 없음
		UNKNOWN
	}

	private final Status status;
	private final String merchantPayKey;
	private final int totalPayAmount;
	private final PaymentFailCode failCode;
	private final PaymentErrorCode errorCode;
	private final String failDetail;

	private NaverPayApproveResult(Status status, String merchantPayKey, int totalPayAmount,
		PaymentFailCode failCode, PaymentErrorCode errorCode, String failDetail) {
		this.status = status;
		this.merchantPayKey = merchantPayKey;
		this.totalPayAmount = totalPayAmount;
		this.failCode = failCode;
		this.errorCode = errorCode;
		this.failDetail = failDetail;
	}

	public static NaverPayApproveResult success(String merchantPayKey, int totalPayAmount) {
		return new NaverPayApproveResult(Status.SUCCESS, merchantPayKey, totalPayAmount, null, null, null);
	}

	public static NaverPayApproveResult processing() {
		return new NaverPayApproveResult(Status.PROCESSING, null, 0, null, null, null);
	}

	public static NaverPayApproveResult alreadyComplete() {
		return new NaverPayApproveResult(Status.ALREADY_COMPLETE, null, 0, null, null, null);
	}

	public static NaverPayApproveResult failed(PaymentFailCode failCode, PaymentErrorCode errorCode,
		String failDetail) {
		return new NaverPayApproveResult(Status.FAILED, null, 0, failCode, errorCode, failDetail);
	}

	public static NaverPayApproveResult unknown(String failDetail) {
		return new NaverPayApproveResult(Status.UNKNOWN, null, 0, null, null, failDetail);
	}
}
