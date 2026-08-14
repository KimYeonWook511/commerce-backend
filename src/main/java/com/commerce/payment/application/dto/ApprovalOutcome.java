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
		UNRESOLVED,
		/**
		 * 우리 기록과 결제사 기록이 어긋나 자동으로 정할 근거가 없다. 상태를 그대로 두고 사람이 볼
		 * 때까지 남긴다 — 미결과 가르는 이유는 그쪽이 결과 불명 표시로 이어지는데, 그 표시는 결제사
		 * 답을 못 받았다는 뜻이라 답을 받은 이 경우에 붙이면 사실과 달라지기 때문이다
		 */
		MANUAL_REVIEW
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

	public static ApprovalOutcome manualReview() {
		return new ApprovalOutcome(Decision.MANUAL_REVIEW, null);
	}
}
