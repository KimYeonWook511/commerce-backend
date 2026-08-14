package com.commerce.payment.legacy.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.postprocess.target.PaymentPostProcessTarget;
import com.commerce.payment.legacy.postprocess.target.PaymentPostProcessTargetPolicy;

class PaymentPostProcessTargetPolicyTest {

	private final PaymentPostProcessTargetPolicy targetPolicy = new PaymentPostProcessTargetPolicy();

	// --- APPROVE UNKNOWN ---

	@DisplayName("APPROVE UNKNOWN이 1분 임계를 경과하지 않으면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveUnknownThresholdNotElapsed_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusSeconds(30));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("APPROVE UNKNOWN이 1분 임계를 경과하면 승인 대사 대상이다")
	@Test
	void resolvePostProcessTarget_approveUnknownThresholdElapsed_returnsApproveReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusMinutes(2));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("APPROVE UNKNOWN이 6시간 escalation 임계를 경과하면 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_approveUnknownEscalated_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusHours(7));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	// --- APPROVE REQUESTED (stale) ---

	@DisplayName("APPROVE REQUESTED가 15분 임계를 경과하지 않으면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveRequestedNotStale_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approvePayment, now.minusMinutes(10));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("APPROVE REQUESTED가 15분 임계를 경과하면 승인 대사 대상이다")
	@Test
	void resolvePostProcessTarget_approveRequestedStale_returnsApproveReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approvePayment, now.minusMinutes(16));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("APPROVE REQUESTED가 6시간 escalation 임계를 경과하면 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_approveRequestedEscalated_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approvePayment, now.minusHours(7));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	// --- APPROVE FAILED ---

	@DisplayName("approve FAILED + MERCHANT_PAY_KEY_MISMATCH면 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_approveFailedMerchantPayKeyMismatch_returnsManualReview() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH, "MISMATCH");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	@DisplayName("approve FAILED + AMOUNT_MISMATCH이고 cancel 기록이 없으면 취소 보상 대상이다")
	@Test
	void resolvePostProcessTarget_approveFailedAmountMismatchNoCancelRecord_returnsApprovedCancelCompensation() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.APPROVED_CANCEL_COMPENSATION);
	}

	@DisplayName("approve FAILED + DUPLICATE_PAYMENT이고 cancel 기록이 없으면 취소 보상 대상이다")
	@Test
	void resolvePostProcessTarget_approveFailedDuplicatePaymentNoCancelRecord_returnsApprovedCancelCompensation() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.DUPLICATE_PAYMENT, "DUPLICATE");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.APPROVED_CANCEL_COMPENSATION);
	}

	@DisplayName("approve FAILED + AMOUNT_MISMATCH이고 cancel 기록이 있으면 취소 보상 대상으로 다시 잡지 않는다")
	@Test
	void resolvePostProcessTarget_approveFailedAmountMismatchWithCancelRecord_doesNotReturnApprovedCancelCompensation() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = failedApprovePayment(PaymentFailCode.AMOUNT_MISMATCH, "AMOUNT_NOT_MATCH");
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		setCreatedAt(cancelPayment, now.minusMinutes(16));

		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(approvePayment, cancelPayment, now);

		assertThat(target).isNotEqualTo(PaymentPostProcessTarget.APPROVED_CANCEL_COMPENSATION);
		assertThat(target).isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	@DisplayName("approve SUCCEEDED면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveSucceeded_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.succeed(now.minusMinutes(1));
		setCreatedAt(approvePayment, now.minusMinutes(30));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve FAILED TIME_EXPIRED는 확정 실패로 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveFailedTimeExpired_returnsNone() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.TIME_EXPIRED, "TIME_EXPIRED");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve FAILED INVALID_MERCHANT는 확정 실패로 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveFailedInvalidMerchant_returnsNone() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.INVALID_MERCHANT, "INVALID_MERCHANT");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve FAILED OWNER_AUTH_FAILED는 확정 실패로 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveFailedOwnerAuthFailed_returnsNone() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.OWNER_AUTH_FAILED, "OWNER_AUTH_FAIL");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("approve FAILED NOT_ENOUGH_ACCOUNT_BALANCE는 확정 실패로 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_approveFailedNotEnoughAccountBalance_returnsNone() {
		Payment approvePayment = failedApprovePayment(PaymentFailCode.NOT_ENOUGH_ACCOUNT_BALANCE, "NOT_ENOUGH");

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, LocalDateTime.now()))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	// --- CANCEL UNKNOWN ---

	@DisplayName("CANCEL UNKNOWN이 1분 임계를 경과하면 취소 대사 대상이다")
	@Test
	void resolvePostProcessTarget_cancelUnknownThresholdElapsed_returnsCancelReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusMinutes(2));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	@DisplayName("CANCEL UNKNOWN이 6시간 escalation 임계를 경과하면 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_cancelUnknownEscalated_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusHours(7));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	// --- CANCEL REQUESTED ---

	@DisplayName("CANCEL REQUESTED가 15분 임계를 경과하면 취소 대사 대상이다")
	@Test
	void resolvePostProcessTarget_cancelRequestedStale_returnsCancelReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		setCreatedAt(cancelPayment, now.minusMinutes(16));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	@DisplayName("CANCEL REQUESTED가 6시간 escalation 임계를 경과하면 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_cancelRequestedEscalated_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		setCreatedAt(cancelPayment, now.minusHours(7));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	// --- CANCEL FAILED ---

	@DisplayName("CANCEL FAILED CANCEL_PROCESS_FAILED면 취소 대사 대상이다")
	@Test
	void resolvePostProcessTarget_cancelFailedCancelProcessFailed_returnsCancelReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.CANCEL_PROCESS_FAILED, "PRE_CANCEL_NOT_COMPLETE",
			now.minusMinutes(1));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	@DisplayName("CANCEL FAILED PG_INVALID_RESPONSE면 취소 대사 대상이다")
	@Test
	void resolvePostProcessTarget_cancelFailedPgInvalidResponse_returnsCancelReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.PG_INVALID_RESPONSE, "INVALID_RESPONSE",
			now.minusMinutes(1));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	@DisplayName("CANCEL FAILED CANCEL_PROCESS_FAILED가 6시간 escalation 임계를 경과하면 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_cancelFailedCancelProcessFailedEscalated_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.CANCEL_PROCESS_FAILED, "PRE_CANCEL_NOT_COMPLETE",
			now.minusHours(7));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	@DisplayName("CANCEL FAILED PG_REQUEST_REJECTED면 시간 무관 수동 검토 대상이다")
	@Test
	void resolvePostProcessTarget_cancelFailedPgRequestRejected_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.PG_REQUEST_REJECTED, "REJECTED",
			now.minusMinutes(1));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	@DisplayName("CANCEL SUCCEEDED면 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_cancelSucceeded_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.succeed(now.minusMinutes(1));
		setCreatedAt(cancelPayment, now.minusMinutes(30));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("CANCEL FAILED INVALID_PG_PAYMENT_ID는 확정 실패로 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_cancelFailedInvalidPgPaymentId_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.INVALID_PG_PAYMENT_ID, "INVALID_ID",
			now.minusMinutes(1));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("CANCEL FAILED INVALID_MERCHANT는 확정 실패로 후처리 대상이 아니다")
	@Test
	void resolvePostProcessTarget_cancelFailedInvalidMerchant_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.INVALID_MERCHANT, "INVALID_MERCHANT",
			now.minusMinutes(1));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	// --- 동시 UNKNOWN 시 approve 우선 ---

	@DisplayName("approve와 cancel이 동시에 UNKNOWN이면 approve를 먼저 확정하는 APPROVE_RECONCILE 대상이다")
	@Test
	void resolvePostProcessTarget_bothApproveAndCancelUnknown_returnsApproveReconcileFirst() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusMinutes(2));

		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusMinutes(2));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	// --- 시각 기준 검증 ---

	@DisplayName("REQUESTED의 경과 시간은 createdAt을 기준으로 계산한다")
	@Test
	void resolvePostProcessTarget_requestedElapsedMeasuredFromCreatedAt() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);

		setCreatedAt(approvePayment, now.minusMinutes(14));
		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);

		setCreatedAt(approvePayment, now.minusMinutes(16));
		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("UNKNOWN의 경과 시간은 respondedAt을 기준으로 계산한다")
	@Test
	void resolvePostProcessTarget_unknownElapsedMeasuredFromRespondedAt() {
		LocalDateTime now = LocalDateTime.now();

		Payment approvePayment1 = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment1.markUnknown("timeout", now.minusSeconds(30));
		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment1, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);

		Payment approvePayment2 = createApproveRequested("PAY-2", "pg-2", 1000);
		approvePayment2.markUnknown("timeout", now.minusMinutes(2));
		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment2, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	// --- 경계·중간 구간 검증 ---

	@DisplayName("APPROVE UNKNOWN이 정확히 1분 임계면 승인 대사 대상이다 (경계 포함)")
	@Test
	void resolvePostProcessTarget_approveUnknownExactlyAtThreshold_returnsApproveReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusMinutes(1));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("APPROVE UNKNOWN이 1분 직전(59초)이면 후처리 대상이 아니다 (경계 미만)")
	@Test
	void resolvePostProcessTarget_approveUnknownJustBeforeThreshold_returnsNone() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusSeconds(59));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.NONE);
	}

	@DisplayName("APPROVE REQUESTED가 정확히 15분 임계면 승인 대사 대상이다 (경계 포함)")
	@Test
	void resolvePostProcessTarget_approveRequestedExactlyAtStaleThreshold_returnsApproveReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approvePayment, now.minusMinutes(15));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("APPROVE UNKNOWN이 정확히 6시간 escalation 임계면 수동 검토 대상이다 (경계 포함)")
	@Test
	void resolvePostProcessTarget_approveUnknownExactlyAtEscalation_returnsManualReview() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusHours(6));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.MANUAL_REVIEW);
	}

	@DisplayName("APPROVE UNKNOWN이 reconcile 구간(1분~6시간) 안이면 escalation 전까지 승인 대사 대상이다")
	@Test
	void resolvePostProcessTarget_approveUnknownWithinReconcileBand_returnsApproveReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		approvePayment.markUnknown("timeout", now.minusHours(3));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("APPROVE REQUESTED가 escalation 직전(5시간 59분)이면 아직 승인 대사 대상이다")
	@Test
	void resolvePostProcessTarget_approveRequestedJustBeforeEscalation_returnsApproveReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = createApproveRequested("PAY-1", "pg-1", 1000);
		setCreatedAt(approvePayment, now.minusHours(5).minusMinutes(59));

		assertThat(targetPolicy.resolvePostProcessTarget(approvePayment, null, now))
			.isEqualTo(PaymentPostProcessTarget.APPROVE_RECONCILE);
	}

	@DisplayName("CANCEL UNKNOWN이 reconcile 구간 안이면 escalation 전까지 취소 대사 대상이다")
	@Test
	void resolvePostProcessTarget_cancelUnknownWithinReconcileBand_returnsCancelReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusHours(3));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	@DisplayName("CANCEL FAILED 재시도 대상이 escalation 구간 안이면 아직 취소 대사 대상이다")
	@Test
	void resolvePostProcessTarget_cancelFailedWithinReconcileBand_returnsCancelReconcile() {
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = failedCancelPayment(PaymentFailCode.CANCEL_PROCESS_FAILED, "PRE_CANCEL_NOT_COMPLETE",
			now.minusHours(3));

		assertThat(targetPolicy.resolvePostProcessTarget(null, cancelPayment, now))
			.isEqualTo(PaymentPostProcessTarget.CANCEL_RECONCILE);
	}

	// --- helpers ---

	private Payment createApproveRequested(String merchantPayKey, String pgPaymentId, int amount) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, amount, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		return Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
	}

	private Payment failedApprovePayment(PaymentFailCode failCode, String failDetail) {
		LocalDateTime now = LocalDateTime.now();
		Payment payment = createApproveRequested("PAY-1", "pg-1", 1000);
		payment.fail(failCode, failDetail, now.minusMinutes(1));
		setCreatedAt(payment, now.minusMinutes(30));
		return payment;
	}

	private Payment failedCancelPayment(PaymentFailCode failCode, String failDetail, LocalDateTime respondedAt) {
		Payment payment = Payment.createCancelRequested(1L, "PAY-1", "pg-1", 1000, PaymentProvider.NAVERPAY);
		payment.fail(failCode, failDetail, respondedAt);
		return payment;
	}

	private void setCreatedAt(Payment payment, LocalDateTime createdAt) {
		ReflectionTestUtils.setField(payment, "createdAt", createdAt);
	}
}
