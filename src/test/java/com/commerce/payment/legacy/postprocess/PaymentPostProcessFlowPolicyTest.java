package com.commerce.payment.legacy.postprocess;

import com.commerce.payment.legacy.postprocess.flow.PaymentPostProcessFlow;
import com.commerce.payment.legacy.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.legacy.postprocess.flow.PaymentVerificationStatus;
import com.commerce.payment.legacy.postprocess.target.PaymentPostProcessTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPostProcessFlowPolicyTest {

	private PaymentPostProcessFlowPolicy policy;

	@BeforeEach
	void setUp() {
		policy = new PaymentPostProcessFlowPolicy();
	}

	// --- APPROVE_RECONCILE ---

	@Test
	void approveReconcile_pgApproved_returnsApprovedPaymentProcess() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_RECONCILE,
			PaymentVerificationStatus.PG_APPROVED
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.APPROVED_PAYMENT_PROCESS);
	}

	@Test
	void approveReconcile_pgCanceled_returnsAlreadyCanceledPaymentProcess() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_RECONCILE,
			PaymentVerificationStatus.PG_CANCELED
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS);
	}

	@Test
	void approveReconcile_pending_returnsKeepWaiting() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_RECONCILE,
			PaymentVerificationStatus.PENDING
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@Test
	void approveReconcile_historyNotFound_returnsKeepWaiting() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_RECONCILE,
			PaymentVerificationStatus.HISTORY_NOT_FOUND
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	// --- CANCEL_RECONCILE ---

	@Test
	void cancelReconcile_pgCanceled_returnsAlreadyCanceledPaymentProcess() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_RECONCILE,
			PaymentVerificationStatus.PG_CANCELED
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS);
	}

	@Test
	void cancelReconcile_pgApproved_returnsCancelRetryProcess() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_RECONCILE,
			PaymentVerificationStatus.PG_APPROVED
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.CANCEL_RETRY_PROCESS);
	}

	@Test
	void cancelReconcile_pending_returnsKeepWaiting() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_RECONCILE,
			PaymentVerificationStatus.PENDING
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@Test
	void cancelReconcile_historyNotFound_returnsKeepWaiting() {
		PaymentPostProcessFlow flow = policy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_RECONCILE,
			PaymentVerificationStatus.HISTORY_NOT_FOUND
		);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	// --- 검증 불필요 경로 ---

	@Test
	void approvedCancelCompensation_returnsCancelAttemptCreationAndCancelRequestProcess() {
		PaymentPostProcessFlow flow = policy.resolveFlow(PaymentPostProcessTarget.APPROVED_CANCEL_COMPENSATION);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS);
	}

	@Test
	void manualReview_returnsManualReviewProcess() {
		PaymentPostProcessFlow flow = policy.resolveFlow(PaymentPostProcessTarget.MANUAL_REVIEW);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.MANUAL_REVIEW_PROCESS);
	}

	@Test
	void none_returnsNone() {
		PaymentPostProcessFlow flow = policy.resolveFlow(PaymentPostProcessTarget.NONE);
		assertThat(flow).isEqualTo(PaymentPostProcessFlow.NONE);
	}

	// --- 예외 경로 ---

	@Test
	void approveReconcile_withoutVerification_throwsIllegalArgumentException() {
		assertThatThrownBy(() -> policy.resolveFlow(PaymentPostProcessTarget.APPROVE_RECONCILE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cancelReconcile_withoutVerification_throwsIllegalArgumentException() {
		assertThatThrownBy(() -> policy.resolveFlow(PaymentPostProcessTarget.CANCEL_RECONCILE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void none_withVerification_throwsIllegalArgumentException() {
		assertThatThrownBy(() -> policy.resolveFlow(PaymentPostProcessTarget.NONE, PaymentVerificationStatus.PG_APPROVED))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void manualReview_withVerification_throwsIllegalArgumentException() {
		assertThatThrownBy(() ->
			policy.resolveFlow(PaymentPostProcessTarget.MANUAL_REVIEW, PaymentVerificationStatus.PG_APPROVED)
		).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void approvedCancelCompensation_withVerification_throwsIllegalArgumentException() {
		assertThatThrownBy(() ->
			policy.resolveFlow(PaymentPostProcessTarget.APPROVED_CANCEL_COMPENSATION, PaymentVerificationStatus.PG_APPROVED)
		).isInstanceOf(IllegalArgumentException.class);
	}
}
