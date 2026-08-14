package com.commerce.payment.application.dto;

import com.commerce.payment.domain.exception.PaymentErrorCode;

/**
 * 승인 확정을 시도한 결과. 진입점이 이 값을 응답이나 다음 처리로 옮긴다.
 *
 * <p>거부와 미결을 가르는 것이 이 목록의 핵심이다 — 거부는 결제가 종결돼 더 볼 것이 없고, 미결은
 * 그 결제가 그대로 남아 대사가 이어받는다. 하나로 합치면 종결된 건까지 대사가 계속 집는다.
 */
public record ApprovalOutcome(Decision decision, PaymentErrorCode errorCode) {

	public enum Decision {
		/** 결제 성공과 주문 완료가 커밋됐다 */
		SUCCEEDED,
		/** 승인은 났는데 받아들이지 않아 결제를 종결했다 */
		REJECTED,
		/** 아무것도 확정하지 못했다. 그 결제는 결과 불명으로 남아 대사가 이어받는다 */
		UNRESOLVED
	}

	public static ApprovalOutcome succeeded() {
		return new ApprovalOutcome(Decision.SUCCEEDED, null);
	}

	public static ApprovalOutcome rejected(PaymentErrorCode errorCode) {
		return new ApprovalOutcome(Decision.REJECTED, errorCode);
	}

	public static ApprovalOutcome unresolved() {
		return new ApprovalOutcome(Decision.UNRESOLVED, null);
	}
}
