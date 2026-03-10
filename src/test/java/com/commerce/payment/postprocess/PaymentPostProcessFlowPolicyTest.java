package com.commerce.payment.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.postprocess.flow.PaymentPostProcessFlow;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.postprocess.flow.PaymentVerificationStatus;
import com.commerce.payment.postprocess.flow.RelatedOrderStatus;
import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;

class PaymentPostProcessFlowPolicyTest {

	private final PaymentPostProcessFlowPolicy flowPolicy = new PaymentPostProcessFlowPolicy();

	@DisplayName("APPROVE_REQUESTED_TARGET은 PG에서 승인 완료로 확인되면 승인 완료 처리로 이어진다")
	@Test
	void resolveFlow_whenApproveRequestedTargetAndVerificationIsPgApproved_returnApprovedPaymentProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET,
			PaymentVerificationStatus.PG_APPROVED
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.APPROVED_PAYMENT_PROCESS);
	}

	@DisplayName("APPROVE_REQUESTED_TARGET은 PG에서 이미 취소된 결제로 확인되면 이미 취소된 결제 처리로 이어진다")
	@Test
	void resolveFlow_whenApproveRequestedTargetAndVerificationIsPgCanceled_returnAlreadyCanceledPaymentProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET,
			PaymentVerificationStatus.PG_CANCELED
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS);
	}

	@DisplayName("APPROVE_REQUESTED_TARGET은 아직 진행 중이면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenApproveRequestedTargetAndVerificationIsPending_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET,
			PaymentVerificationStatus.PENDING
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("APPROVE_REQUESTED_TARGET은 history가 없으면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenApproveRequestedTargetAndVerificationIsHistoryNotFound_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET,
			PaymentVerificationStatus.HISTORY_NOT_FOUND
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("FAILED_APPROVE_RESULT_TARGET은 PG에서 승인 완료로 확인되면 승인 완료 처리로 이어진다")
	@Test
	void resolveFlow_whenFailedApproveResultTargetAndVerificationIsPgApproved_returnApprovedPaymentProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET,
			PaymentVerificationStatus.PG_APPROVED
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.APPROVED_PAYMENT_PROCESS);
	}

	@DisplayName("FAILED_APPROVE_RESULT_TARGET은 PG에서 이미 취소된 결제로 확인되면 이미 취소된 결제 처리로 이어진다")
	@Test
	void resolveFlow_whenFailedApproveResultTargetAndVerificationIsPgCanceled_returnAlreadyCanceledPaymentProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET,
			PaymentVerificationStatus.PG_CANCELED
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS);
	}

	@DisplayName("FAILED_APPROVE_RESULT_TARGET은 아직 진행 중이면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenFailedApproveResultTargetAndVerificationIsPending_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET,
			PaymentVerificationStatus.PENDING
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("FAILED_APPROVE_RESULT_TARGET은 history가 없으면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenFailedApproveResultTargetAndVerificationIsHistoryNotFound_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET,
			PaymentVerificationStatus.HISTORY_NOT_FOUND
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("APPROVED_PAYMENT_CANCEL_ACTION은 cancel attempt 생성과 취소 요청 처리로 이어진다")
	@Test
	void resolveFlow_whenApprovedPaymentCancelAction_returnCancelAttemptCreationAndCancelRequestProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS);
	}

	@DisplayName("CANCEL_REQUESTED_TARGET은 PG에서 이미 취소된 결제로 확인되면 이미 취소된 결제 처리로 이어진다")
	@Test
	void resolveFlow_whenCancelRequestedTargetAndVerificationIsPgCanceled_returnAlreadyCanceledPaymentProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET,
			PaymentVerificationStatus.PG_CANCELED
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS);
	}

	@DisplayName("CANCEL_REQUESTED_TARGET은 PG에서 승인 상태로 확인되면 취소 재시도로 이어진다")
	@Test
	void resolveFlow_whenCancelRequestedTargetAndVerificationIsPgApproved_returnCancelRetryProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET,
			PaymentVerificationStatus.PG_APPROVED
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.CANCEL_RETRY_PROCESS);
	}

	@DisplayName("CANCEL_REQUESTED_TARGET은 아직 진행 중이면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenCancelRequestedTargetAndVerificationIsPending_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET,
			PaymentVerificationStatus.PENDING
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("CANCEL_REQUESTED_TARGET은 history가 없으면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenCancelRequestedTargetAndVerificationIsHistoryNotFound_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET,
			PaymentVerificationStatus.HISTORY_NOT_FOUND
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("CANCEL_RETRY_TARGET은 취소 재시도 처리로 이어진다")
	@Test
	void resolveFlow_whenCancelRetryTarget_returnCancelRetryProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(PaymentPostProcessTarget.CANCEL_RETRY_TARGET);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.CANCEL_RETRY_PROCESS);
	}

	@DisplayName("MANUAL_REVIEW_TARGET은 수동 확인 대상으로 남긴다")
	@Test
	void resolveFlow_whenManualReviewTarget_returnManualReviewProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.MANUAL_REVIEW_PROCESS);
	}

	@DisplayName("MERCHANT_PAY_KEY_MISMATCH_TARGET은 PG 승인 완료이고 실제 merchantPayKey 주문이 PAID면 추가 후처리 없이 종료한다")
	@Test
	void resolveFlow_whenMerchantPayKeyMismatchTargetAndVerificationIsPgApprovedWithPaidOrder_returnNone() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET,
			PaymentVerificationStatus.PG_APPROVED,
			RelatedOrderStatus.PAID
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.NONE);
	}

	@DisplayName("MERCHANT_PAY_KEY_MISMATCH_TARGET은 PG 승인 완료이고 실제 merchantPayKey 주문이 INIT면 취소 보상 처리로 이어진다")
	@Test
	void resolveFlow_whenMerchantPayKeyMismatchTargetAndVerificationIsPgApprovedWithInitOrder_returnCancelAttemptCreationAndCancelRequestProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET,
			PaymentVerificationStatus.PG_APPROVED,
			RelatedOrderStatus.INIT
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS);
	}

	@DisplayName("MERCHANT_PAY_KEY_MISMATCH_TARGET은 PG 승인 완료여도 실제 merchantPayKey 주문을 찾지 못하면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenMerchantPayKeyMismatchTargetAndVerificationIsPgApprovedWithOrderNotFound_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET,
			PaymentVerificationStatus.PG_APPROVED,
			RelatedOrderStatus.NOT_FOUND
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("MERCHANT_PAY_KEY_MISMATCH_TARGET은 PG에서 이미 취소된 결제로 확인되면 이미 취소된 결제 처리로 이어진다")
	@Test
	void resolveFlow_whenMerchantPayKeyMismatchTargetAndVerificationIsPgCanceled_returnAlreadyCanceledPaymentProcess() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET,
			PaymentVerificationStatus.PG_CANCELED,
			RelatedOrderStatus.INIT
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.ALREADY_CANCELED_PAYMENT_PROCESS);
	}

	@DisplayName("MERCHANT_PAY_KEY_MISMATCH_TARGET은 아직 진행 중이면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenMerchantPayKeyMismatchTargetAndVerificationIsPending_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET,
			PaymentVerificationStatus.PENDING,
			RelatedOrderStatus.INIT
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}

	@DisplayName("MERCHANT_PAY_KEY_MISMATCH_TARGET은 history가 없으면 다음 주기까지 대기한다")
	@Test
	void resolveFlow_whenMerchantPayKeyMismatchTargetAndVerificationIsHistoryNotFound_returnKeepWaiting() {
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(
			PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET,
			PaymentVerificationStatus.HISTORY_NOT_FOUND,
			RelatedOrderStatus.INIT
		);

		assertThat(flow).isEqualTo(PaymentPostProcessFlow.KEEP_WAITING);
	}
}
