package com.commerce.payment.postprocess.flow;

import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;

public class PaymentPostProcessFlowPolicy {

	public PaymentPostProcessFlow resolveFlow(PaymentPostProcessTarget target) {
		return switch (target) {
			case APPROVED_CANCEL_COMPENSATION -> PaymentPostProcessFlow.CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS;
			case MANUAL_REVIEW -> PaymentPostProcessFlow.MANUAL_REVIEW_PROCESS;
			case NONE -> PaymentPostProcessFlow.NONE;
			default -> throw new IllegalArgumentException("Verification status is required for target: " + target);
		};
	}

	public PaymentPostProcessFlow resolveFlow(
		PaymentPostProcessTarget target,
		PaymentVerificationStatus verificationStatus
	) {
		return switch (target) {
			case APPROVE_RECONCILE -> switch (verificationStatus) {
				case PG_APPROVED -> PaymentPostProcessFlow.APPROVED_PAYMENT_PROCESS;
				case PG_CANCELED -> PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS;
				case PENDING, HISTORY_NOT_FOUND -> PaymentPostProcessFlow.KEEP_WAITING;
			};
			case CANCEL_RECONCILE -> switch (verificationStatus) {
				case PG_CANCELED -> PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS;
				case PG_APPROVED -> PaymentPostProcessFlow.CANCEL_RETRY_PROCESS;
				case PENDING, HISTORY_NOT_FOUND -> PaymentPostProcessFlow.KEEP_WAITING;
			};
			default -> throw new IllegalArgumentException("Verification status is not supported for target: " + target);
		};
	}
}
