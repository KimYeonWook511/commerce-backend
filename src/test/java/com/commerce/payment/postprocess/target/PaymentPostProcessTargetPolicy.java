package com.commerce.payment.postprocess.target;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;

public class PaymentPostProcessTargetPolicy {

	private static final Duration APPROVE_REQUEST_DELAY = Duration.ofMinutes(5);
	private static final Duration CANCEL_REQUEST_DELAY = Duration.ofMinutes(5);
	private static final Duration CANCEL_FAILED_DELAY = Duration.ofMinutes(5);

	private static final EnumSet<PaymentAttemptFailCode> FAILED_APPROVE_RESULT_CODES = EnumSet.of(
		PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
		PaymentAttemptFailCode.PG_INVALID_RESPONSE,
		PaymentAttemptFailCode.PG_NETWORK_ERROR,
		PaymentAttemptFailCode.PG_SERVER_ERROR
	);

	public PaymentPostProcessTarget resolvePostProcessTarget(PaymentAttempt approveAttempt, PaymentAttempt cancelAttempt, LocalDateTime now) {
		if (approveAttempt != null) {
			if (approveAttempt.getStatus() == PaymentAttemptStatus.REQUESTED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)) {
				return PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET;
			}
			if (approveAttempt.getStatus() == PaymentAttemptStatus.FAILED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)
				&& cancelAttempt == null
				&& approveAttempt.getFailCode() == PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH) {
				return PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET;
			}
			if (approveAttempt.getStatus() == PaymentAttemptStatus.FAILED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)
				&& cancelAttempt == null
				&& FAILED_APPROVE_RESULT_CODES.contains(approveAttempt.getFailCode())) {
				return PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET;
			}
			if (approveAttempt.getStatus() == PaymentAttemptStatus.FAILED
				&& hasElapsed(approveAttempt, APPROVE_REQUEST_DELAY, now)
				&& cancelAttempt == null
				&& isCancelCompensationRequired(approveAttempt.getFailCode())) {
				return PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION;
			}
		}

		if (cancelAttempt != null) {
			if (cancelAttempt.getStatus() == PaymentAttemptStatus.REQUESTED
				&& hasElapsed(cancelAttempt, CANCEL_REQUEST_DELAY, now)) {
				return PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET;
			}
			if (cancelAttempt.getStatus() == PaymentAttemptStatus.FAILED
				&& hasElapsed(cancelAttempt, CANCEL_FAILED_DELAY, now)
				&& (cancelAttempt.getFailCode() == PaymentAttemptFailCode.PG_INVALID_RESPONSE
				|| cancelAttempt.getFailCode() == PaymentAttemptFailCode.CANCEL_PROCESS_FAILED)) {
				return PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET;
			}
			if (cancelAttempt.getStatus() == PaymentAttemptStatus.FAILED
				&& hasElapsed(cancelAttempt, CANCEL_FAILED_DELAY, now)
				&& cancelAttempt.getFailCode() == PaymentAttemptFailCode.PG_REQUEST_REJECTED) {
				return PaymentPostProcessTarget.MANUAL_REVIEW_TARGET;
			}
		}

		return PaymentPostProcessTarget.NONE;
	}

	private boolean isCancelCompensationRequired(PaymentAttemptFailCode failCode) {
		return failCode == PaymentAttemptFailCode.AMOUNT_MISMATCH
			|| failCode == PaymentAttemptFailCode.DUPLICATE_PAYMENT;
	}

	private boolean hasElapsed(PaymentAttempt attempt, Duration delay, LocalDateTime now) {
		return !attempt.getCreatedAt().plus(delay).isAfter(now);
	}

}
