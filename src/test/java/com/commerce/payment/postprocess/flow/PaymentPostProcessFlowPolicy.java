package com.commerce.payment.postprocess.flow;

import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;

public class PaymentPostProcessFlowPolicy {

	public PaymentPostProcessFlow resolveFlow(PaymentPostProcessTarget target) {
		return switch (target) {
			case APPROVED_PAYMENT_CANCEL_ACTION -> PaymentPostProcessFlow.CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS;
			case CANCEL_RETRY_TARGET -> PaymentPostProcessFlow.CANCEL_RETRY_PROCESS;
			case MANUAL_REVIEW_TARGET -> PaymentPostProcessFlow.MANUAL_REVIEW_PROCESS;
			case NONE -> PaymentPostProcessFlow.NONE;
			default -> throw new IllegalArgumentException("Verification status is required for target: " + target);
		};
	}

	public PaymentPostProcessFlow resolveFlow(
		PaymentPostProcessTarget target,
		PaymentVerificationStatus verificationStatus
	) {
		return switch (target) {
			case APPROVE_REQUESTED_TARGET, FAILED_APPROVE_RESULT_TARGET -> switch (verificationStatus) {
				case PG_APPROVED -> PaymentPostProcessFlow.APPROVED_PAYMENT_PROCESS;
				case PG_CANCELED -> PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS;
				case PENDING, HISTORY_NOT_FOUND -> PaymentPostProcessFlow.KEEP_WAITING;
			};
			case CANCEL_REQUESTED_TARGET -> switch (verificationStatus) {
				case PG_CANCELED -> PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS;
				case PG_APPROVED -> PaymentPostProcessFlow.CANCEL_RETRY_PROCESS;
				case PENDING, HISTORY_NOT_FOUND -> PaymentPostProcessFlow.KEEP_WAITING;
			};
			default -> throw new IllegalArgumentException("Verification status is not supported for target: " + target);
		};
	}

	public PaymentPostProcessFlow resolveFlow(
		PaymentPostProcessTarget target,
		PaymentVerificationStatus verificationStatus,
		RelatedOrderStatus relatedOrderStatus
	) {
		if (target != PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET) {
			throw new IllegalArgumentException("Related order status is not supported for target: " + target);
		}

		return switch (verificationStatus) {
			case PG_APPROVED -> switch (relatedOrderStatus) {
				case PAID -> PaymentPostProcessFlow.NONE;
				case INIT -> PaymentPostProcessFlow.CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS;
				case NOT_FOUND -> PaymentPostProcessFlow.KEEP_WAITING;
			};
			case PG_CANCELED -> PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS;
			case PENDING, HISTORY_NOT_FOUND -> PaymentPostProcessFlow.KEEP_WAITING;
		};
	}
}
