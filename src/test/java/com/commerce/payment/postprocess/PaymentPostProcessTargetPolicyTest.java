package com.commerce.payment.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;
import com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy;

class PaymentPostProcessTargetPolicyTest {

	private final PaymentPostProcessTargetPolicy targetPolicy = new PaymentPostProcessTargetPolicy();

	@DisplayName("후처리 기준 시간이 지나지 않으면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenThresholdNotElapsed_returnNone() {
		LocalDateTime now = LocalDateTime.now();

		PaymentAttempt approveRequestedAttempt = PaymentAttempt.createApproveRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(approveRequestedAttempt, now.minusMinutes(4));

		PaymentAttempt cancelRequestedAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelRequestedAttempt, now.minusMinutes(4));

		PaymentAttempt failedCancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		failedCancelAttempt.markCancelFailed(PaymentAttemptFailCode.CANCEL_PROCESS_FAILED, "PRE_CANCEL_NOT_COMPLETE",
			now.minusMinutes(1));
		setCreatedAt(failedCancelAttempt, now.minusMinutes(4));

		PaymentAttempt amountMismatchAttempt = PaymentAttempt.createApproveRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		amountMismatchAttempt.markApproveFailed(PaymentAttemptFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH",
			now.minusMinutes(1));
		setCreatedAt(amountMismatchAttempt, now.minusMinutes(4));

		assertThat(targetPolicy.resolvePostProcessTarget(approveRequestedAttempt, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelRequestedAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
		assertThat(targetPolicy.resolvePostProcessTarget(null, failedCancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
		assertThat(targetPolicy.resolvePostProcessTarget(amountMismatchAttempt, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 SUCCEEDED면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptSucceeded_returnNone() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt approveAttempt = PaymentAttempt.createApproveRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		approveAttempt.markApproveSucceeded(now.minusMinutes(1));
		setCreatedAt(approveAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 TIME_EXPIRED로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByTimeExpired_returnNone() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.TIME_EXPIRED, "TIME_EXPIRED");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 PG_REQUEST_REJECTED로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgRequestRejected_returnNone() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 INVALID_MERCHANT로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByInvalidMerchant_returnNone() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.INVALID_MERCHANT, "INVALID_MERCHANT");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 OWNER_AUTH_FAILED로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByOwnerAuthFailed_returnNone() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.OWNER_AUTH_FAILED,
			"OWNER_AUTH_FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 NOT_ENOUGH_ACCOUNT_BALANCE로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByNotEnoughAccountBalance_returnNone() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.NOT_ENOUGH_ACCOUNT_BALANCE,
			"NOT_ENOUGH_ACCOUNT_BALANCE");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 REQUESTED이고 기준 시간이 지나면 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptRequested_returnApproveRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt approveAttempt = PaymentAttempt.createApproveRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(approveAttempt, now.minusMinutes(5));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 MERCHANT_PAY_KEY_MISMATCH로 실패하면 관련 주문 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByMerchantPayKeyMismatch_returnMerchantPayKeyMismatchTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(
			PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH,
			"MERCHANT_PAY_KEY_MISMATCH"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET);
	}

	@DisplayName("approve attempt가 APPROVE_PROCESS_FAILED로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByApproveProcessFailed_returnFailedApproveResultTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(
			PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
			"APPROVE_PROCESS_FAILED"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 PG_INVALID_RESPONSE로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgInvalidResponse_returnFailedApproveResultTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(
			PaymentAttemptFailCode.PG_INVALID_RESPONSE,
			"INVALID_RESPONSE"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 PG_NETWORK_ERROR로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgNetworkError_returnFailedApproveResultTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(
			PaymentAttemptFailCode.PG_NETWORK_ERROR,
			"NETWORK_ERROR"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 PG_SERVER_ERROR로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgServerError_returnFailedApproveResultTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(
			PaymentAttemptFailCode.PG_SERVER_ERROR,
			"SERVER_ERROR"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패하고 cancel attempt가 없으면 취소 보상 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByAmountMismatchWithoutCancelAttempt_returnApprovedPaymentCancelAction() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION);
	}

	@DisplayName("approve attempt가 DUPLICATE_PAYMENT로 실패하고 cancel attempt가 없으면 취소 보상 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByDuplicatePaymentWithoutCancelAttempt_returnApprovedPaymentCancelAction() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.DUPLICATE_PAYMENT,
			"DUPLICATE_PAYMENT");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION);
	}

	@DisplayName("cancel attempt가 SUCCEEDED면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptSucceeded_returnNone() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		cancelAttempt.markCancelSucceeded(now.minusMinutes(1));
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("cancel attempt가 INVALID_PAYMENT_ID로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByInvalidPaymentId_returnNone() {
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.INVALID_PAYMENT_ID,
			"INVALID_PAYMENT_ID");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("cancel attempt가 INVALID_MERCHANT로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByInvalidMerchant_returnNone() {
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.INVALID_MERCHANT, "INVALID_MERCHANT");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("cancel attempt가 REQUESTED이고 기준 시간이 지나면 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptRequested_returnCancelRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(5));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패하고 cancel attempt가 REQUESTED면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenAmountMismatchAndCancelAttemptRequested_returnCancelRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패한 뒤 cancel attempt가 생기면 같은 취소 보상 대상으로 다시 잡지 않는다")
	@Test
	void resolvePostProcessTarget_whenAmountMismatchAlreadyProcessedWithCancelAttempt_doNotReturnApprovedPaymentCancelAction() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now);

		assertThat(target).isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
		assertThat(target).isNotEqualTo(PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION);
	}

	@DisplayName("approve attempt가 DUPLICATE_PAYMENT로 실패하고 cancel attempt가 REQUESTED면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenDuplicatePaymentAndCancelAttemptRequested_returnCancelRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.DUPLICATE_PAYMENT,
			"DUPLICATE_PAYMENT");
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 MERCHANT_PAY_KEY_MISMATCH로 실패한 뒤 cancel attempt가 생기면 같은 mismatch 확인 대상으로 다시 잡지 않는다")
	@Test
	void resolvePostProcessTarget_whenMerchantPayKeyMismatchAlreadyProcessedWithCancelAttempt_doNotReturnMerchantPayKeyMismatchTarget() {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt approveAttempt = failedApproveAttempt(
			PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH,
			"MERCHANT_PAY_KEY_MISMATCH"
		);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now);

		assertThat(target).isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
		assertThat(target).isNotEqualTo(PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET);
	}

	@DisplayName("cancel attempt가 PG_INVALID_RESPONSE로 실패하면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByPgInvalidResponse_returnCancelRequestedTarget() {
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.PG_INVALID_RESPONSE,
			"INVALID_RESPONSE");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("cancel attempt가 CANCEL_PROCESS_FAILED로 실패하면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByCancelProcessFailed_returnCancelRequestedTarget() {
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.CANCEL_PROCESS_FAILED,
			"CANCEL_NOT_COMPLETE");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("cancel attempt가 PG_REQUEST_REJECTED로 실패하면 수동 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByPgRequestRejected_returnManualReviewTarget() {
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패하고 cancel attempt가 PG_REQUEST_REJECTED로 실패하면 수동 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenAmountMismatchAndCancelAttemptFailedByPgRequestRejected_returnManualReviewTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);
	}

	@DisplayName("approve attempt가 DUPLICATE_PAYMENT로 실패하고 cancel attempt가 PG_REQUEST_REJECTED로 실패하면 수동 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenDuplicatePaymentAndCancelAttemptFailedByPgRequestRejected_returnManualReviewTarget() {
		PaymentAttempt approveAttempt = failedApproveAttempt(PaymentAttemptFailCode.DUPLICATE_PAYMENT,
			"DUPLICATE_PAYMENT");
		PaymentAttempt cancelAttempt = failedCancelAttempt(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);
	}

	private PaymentAttempt failedApproveAttempt(PaymentAttemptFailCode failCode, String failDetail) {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.markApproveFailed(failCode, failDetail, now.minusMinutes(1));
		setCreatedAt(attempt, now.minusMinutes(30));
		return attempt;
	}

	private PaymentAttempt failedCancelAttempt(PaymentAttemptFailCode failCode, String failDetail) {
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested(
			"PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.markCancelFailed(failCode, failDetail, now.minusMinutes(1));
		setCreatedAt(attempt, now.minusMinutes(10));
		return attempt;
	}

	private void setCreatedAt(PaymentAttempt attempt, LocalDateTime createdAt) {
		ReflectionTestUtils.setField(attempt, "createdAt", createdAt);
	}
}
