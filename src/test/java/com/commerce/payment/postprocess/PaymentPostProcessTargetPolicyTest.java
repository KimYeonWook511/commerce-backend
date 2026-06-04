package com.commerce.payment.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;
import com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy;

class PaymentPostProcessTargetPolicyTest {

	private final PaymentPostProcessTargetPolicy targetPolicy = new PaymentPostProcessTargetPolicy();

	@DisplayName("후처리 기준 시간이 지나지 않으면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenThresholdNotElapsed_returnNone() {
		LocalDateTime now = LocalDateTime.now();

		Payment approveRequestedAttempt = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approveRequestedAttempt, now.minusMinutes(4));

		Payment cancelRequestedAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelRequestedAttempt, now.minusMinutes(4));

		Payment failedCancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		failedCancelAttempt.fail(PaymentFailCode.CANCEL_PROCESS_FAILED, "PRE_CANCEL_NOT_COMPLETE",
			now.minusMinutes(1));
		setCreatedAt(failedCancelAttempt, now.minusMinutes(4));

		Payment amountMismatchAttempt = createApproveRequested("PAY-1", "pg-1", 1000);
		amountMismatchAttempt.fail(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH",
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
		Payment approveAttempt = createApproveRequested("PAY-1", "pg-1", 1000);
		approveAttempt.succeed(now.minusMinutes(1));
		setCreatedAt(approveAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 TIME_EXPIRED로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByTimeExpired_returnNone() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.TIME_EXPIRED, "TIME_EXPIRED");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 PG_REQUEST_REJECTED로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgRequestRejected_returnNone() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 INVALID_MERCHANT로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByInvalidMerchant_returnNone() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.INVALID_MERCHANT, "INVALID_MERCHANT");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 OWNER_AUTH_FAILED로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByOwnerAuthFailed_returnNone() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.OWNER_AUTH_FAILED, "OWNER_AUTH_FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 NOT_ENOUGH_ACCOUNT_BALANCE로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByNotEnoughAccountBalance_returnNone() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.NOT_ENOUGH_ACCOUNT_BALANCE,
			"NOT_ENOUGH_ACCOUNT_BALANCE");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve attempt가 REQUESTED이고 기준 시간이 지나면 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptRequested_returnApproveRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		Payment approveAttempt = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approveAttempt, now.minusMinutes(5));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 MERCHANT_PAY_KEY_MISMATCH로 실패하면 관련 주문 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByMerchantPayKeyMismatch_returnMerchantPayKeyMismatchTarget() {
		Payment approveAttempt = failedApproveAttempt(
			PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH,
			"MERCHANT_PAY_KEY_MISMATCH"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET);
	}

	@DisplayName("approve attempt가 APPROVE_PROCESS_FAILED로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByApproveProcessFailed_returnFailedApproveResultTarget() {
		Payment approveAttempt = failedApproveAttempt(
			PaymentFailCode.APPROVE_PROCESS_FAILED,
			"APPROVE_PROCESS_FAILED"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 PG_INVALID_RESPONSE로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgInvalidResponse_returnFailedApproveResultTarget() {
		Payment approveAttempt = failedApproveAttempt(
			PaymentFailCode.PG_INVALID_RESPONSE,
			"INVALID_RESPONSE"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 PG_NETWORK_ERROR로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgNetworkError_returnFailedApproveResultTarget() {
		Payment approveAttempt = failedApproveAttempt(
			PaymentFailCode.PG_NETWORK_ERROR,
			"NETWORK_ERROR"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 PG_SERVER_ERROR로 실패하면 승인 결과 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByPgServerError_returnFailedApproveResultTarget() {
		Payment approveAttempt = failedApproveAttempt(
			PaymentFailCode.PG_SERVER_ERROR,
			"SERVER_ERROR"
		);

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.FAILED_APPROVE_RESULT_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패하고 cancel attempt가 없으면 취소 보상 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByAmountMismatchWithoutCancelAttempt_returnApprovedPaymentCancelAction() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION);
	}

	@DisplayName("approve attempt가 DUPLICATE_PAYMENT로 실패하고 cancel attempt가 없으면 취소 보상 대상이다")
	@Test
	void resolvePostProcessTarget_whenApproveAttemptFailedByDuplicatePaymentWithoutCancelAttempt_returnApprovedPaymentCancelAction() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.DUPLICATE_PAYMENT, "DUPLICATE_PAYMENT");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.APPROVED_PAYMENT_CANCEL_ACTION);
	}

	@DisplayName("cancel attempt가 SUCCEEDED면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptSucceeded_returnNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		cancelAttempt.succeed(now.minusMinutes(1));
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("cancel attempt가 INVALID_PG_PAYMENT_ID로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByInvalidPgPaymentId_returnNone() {
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.INVALID_PG_PAYMENT_ID, "INVALID_PG_PAYMENT_ID");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("cancel attempt가 INVALID_MERCHANT로 실패하면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByInvalidMerchant_returnNone() {
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.INVALID_MERCHANT, "INVALID_MERCHANT");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("cancel attempt가 REQUESTED이고 기준 시간이 지나면 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptRequested_returnCancelRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(5));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패하고 cancel attempt가 REQUESTED면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenAmountMismatchAndCancelAttemptRequested_returnCancelRequestedTarget() {
		LocalDateTime now = LocalDateTime.now();
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		Payment cancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패한 뒤 cancel attempt가 생기면 같은 취소 보상 대상으로 다시 잡지 않는다")
	@Test
	void resolvePostProcessTarget_whenAmountMismatchAlreadyProcessedWithCancelAttempt_doNotReturnApprovedPaymentCancelAction() {
		LocalDateTime now = LocalDateTime.now();
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		Payment cancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
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
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.DUPLICATE_PAYMENT, "DUPLICATE_PAYMENT");
		Payment cancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("approve attempt가 MERCHANT_PAY_KEY_MISMATCH로 실패한 뒤 cancel attempt가 생기면 같은 mismatch 확인 대상으로 다시 잡지 않는다")
	@Test
	void resolvePostProcessTarget_whenMerchantPayKeyMismatchAlreadyProcessedWithCancelAttempt_doNotReturnMerchantPayKeyMismatchTarget() {
		LocalDateTime now = LocalDateTime.now();
		Payment approveAttempt = failedApproveAttempt(
			PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH,
			"MERCHANT_PAY_KEY_MISMATCH"
		);
		Payment cancelAttempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		setCreatedAt(cancelAttempt, now.minusMinutes(10));

		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, now);

		assertThat(target).isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
		assertThat(target).isNotEqualTo(PaymentPostProcessTarget.MERCHANT_PAY_KEY_MISMATCH_TARGET);
	}

	@DisplayName("cancel attempt가 PG_INVALID_RESPONSE로 실패하면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByPgInvalidResponse_returnCancelRequestedTarget() {
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.PG_INVALID_RESPONSE, "INVALID_RESPONSE");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("cancel attempt가 CANCEL_PROCESS_FAILED로 실패하면 취소 상태 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByCancelProcessFailed_returnCancelRequestedTarget() {
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.CANCEL_PROCESS_FAILED, "CANCEL_NOT_COMPLETE");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_REQUESTED_TARGET);
	}

	@DisplayName("cancel attempt가 PG_REQUEST_REJECTED로 실패하면 수동 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenCancelAttemptFailedByPgRequestRejected_returnManualReviewTarget() {
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);
	}

	@DisplayName("approve attempt가 AMOUNT_MISMATCH로 실패하고 cancel attempt가 PG_REQUEST_REJECTED로 실패하면 수동 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenAmountMismatchAndCancelAttemptFailedByPgRequestRejected_returnManualReviewTarget() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);
	}

	@DisplayName("approve attempt가 DUPLICATE_PAYMENT로 실패하고 cancel attempt가 PG_REQUEST_REJECTED로 실패하면 수동 확인 대상이다")
	@Test
	void resolvePostProcessTarget_whenDuplicatePaymentAndCancelAttemptFailedByPgRequestRejected_returnManualReviewTarget() {
		Payment approveAttempt = failedApproveAttempt(PaymentFailCode.DUPLICATE_PAYMENT, "DUPLICATE_PAYMENT");
		Payment cancelAttempt = failedCancelAttempt(PaymentFailCode.PG_REQUEST_REJECTED, "FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approveAttempt, cancelAttempt, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW_TARGET);
	}

	private Payment createApproveRequested(String merchantPayKey, String pgPaymentId, int amount) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, amount, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		return Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
	}

	private Payment failedApproveAttempt(PaymentFailCode failCode, String failDetail) {
		LocalDateTime now = LocalDateTime.now();
		Payment attempt = createApproveRequested("PAY-1", "pg-1", 1000);
		attempt.fail(failCode, failDetail, now.minusMinutes(1));
		setCreatedAt(attempt, now.minusMinutes(30));
		return attempt;
	}

	private Payment failedCancelAttempt(PaymentFailCode failCode, String failDetail) {
		LocalDateTime now = LocalDateTime.now();
		Payment attempt = Payment.createCancelRequested(
			1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.fail(failCode, failDetail, now.minusMinutes(1));
		setCreatedAt(attempt, now.minusMinutes(10));
		return attempt;
	}

	private void setCreatedAt(Payment attempt, LocalDateTime createdAt) {
		ReflectionTestUtils.setField(attempt, "createdAt", createdAt);
	}
}
