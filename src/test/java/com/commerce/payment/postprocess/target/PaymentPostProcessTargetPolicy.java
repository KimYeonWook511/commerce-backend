package com.commerce.payment.postprocess.target;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentStatus;

public class PaymentPostProcessTargetPolicy {

	private static final Duration APPROVE_REQUEST_DELAY = Duration.ofMinutes(5);
	private static final Duration CANCEL_REQUEST_DELAY = Duration.ofMinutes(5);
	private static final Duration CANCEL_FAILED_DELAY = Duration.ofMinutes(5);

	private static final EnumSet<PaymentFailCode> FAILED_APPROVE_RESULT_CODES = EnumSet.of(
		PaymentFailCode.APPROVE_PROCESS_FAILED,
		PaymentFailCode.PG_INVALID_RESPONSE,
		PaymentFailCode.PG_NETWORK_ERROR,
		PaymentFailCode.PG_SERVER_ERROR
	);

	public PaymentPostProcessTarget resolvePostProcessTarget(Payment approveAttempt, Payment cancelAttempt, LocalDateTime now) {
		if (approveAttempt != null) {
			if (approveAttempt.getStatus() == PaymentStatus.REQUESTED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)) {
				return PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET;
			}
			if (approveAttempt.getStatus() == PaymentStatus.FAILED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)
				&& cancelAttempt == null
				&& approveAttempt.getFailCode() == PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH) {
				return PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET;
			}
			if (approveAttempt.getStatus() == PaymentStatus.FAILED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)
				&& cancelAttempt == null
				&& FAILED_APPROVE_RESULT_CODES.contains(approveAttempt.getFailCode())) {
				return PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET;
			}
			if (approveAttempt.getStatus() == PaymentStatus.FAILED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)
				&& cancelAttempt == null
				&& isCancelCompensationRequired(approveAttempt.getFailCode())) {
				return PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION;
			}
		}

		if (cancelAttempt != null) {
			if (cancelAttempt.getStatus() == PaymentStatus.REQUESTED
				&& hasElapsed(cancelAttempt, CANCEL_REQUEST_DELAY, now)) {
				return PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET;
			}
			if (cancelAttempt.getStatus() == PaymentStatus.FAILED
				&& hasElapsed(cancelAttempt, CANCEL_FAILED_DELAY, now)
				&& (cancelAttempt.getFailCode() == PaymentFailCode.PG_INVALID_RESPONSE
				|| cancelAttempt.getFailCode() == PaymentFailCode.CANCEL_PROCESS_FAILED)) {
				return PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET;
			}
			if (cancelAttempt.getStatus() == PaymentStatus.FAILED
				&& hasElapsed(cancelAttempt, CANCEL_FAILED_DELAY, now)
				&& cancelAttempt.getFailCode() == PaymentFailCode.PG_REQUEST_REJECTED) {
				return PaymentPostProcessTarget.MANUAL_REVIEW_TARGET;
			}
		}

		return PaymentPostProcessTarget.NONE;
	}

	private boolean isCancelCompensationRequired(PaymentFailCode failCode) {
		return failCode == PaymentFailCode.AMOUNT_MISMATCH
			|| failCode == PaymentFailCode.DUPLICATE_PAYMENT;
	}

	private boolean hasElapsed(Payment attempt, Duration delay, LocalDateTime now) {
		return !attempt.getCreatedAt().plus(delay).isAfter(now);
	}

}
