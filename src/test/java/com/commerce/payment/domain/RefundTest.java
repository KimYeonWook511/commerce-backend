package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

class RefundTest {

	private static final Long PAYMENT_ID = 55L;
	private static final String REFUND_KEY = "RF-0123456789abcdef0123456789abcd";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	private Refund requestedRefund() {
		return Refund.open(PAYMENT_ID, REFUND_KEY, RefundRequester.MEMBER, "IDEM-1", 10_000,
			RefundReason.ORDER_CANCELED);
	}

	private Refund inProgressRefund() {
		Refund refund = requestedRefund();
		refund.markInProgress(NOW);
		return refund;
	}

	@DisplayName("환불 사건을 열면 아직 안 보낸 상태가 되고 시도 번호가 0이다")
	@Test
	void open_whenCalled_createsRequestedRefundWithoutAttempt() {
		Refund refund = requestedRefund();

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
		assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(refund.getAmount()).isEqualTo(10_000);
		assertThat(refund.getReason()).isEqualTo(RefundReason.ORDER_CANCELED);
		assertThat(refund.getAttemptSeq()).isZero();
		assertThat(refund.getReconcileCount()).isZero();
		assertThat(refund.getPgIdempotencyKey()).isNotBlank();
	}

	@DisplayName("같은 요청 키로 온 요청은 금액과 사유가 모두 같아야 앞서 만든 사건으로 받아들여진다")
	@Test
	void requireSameRequest_whenContentDiffers_throws() {
		Refund refund = requestedRefund();

		refund.requireSameRequest(10_000, RefundReason.ORDER_CANCELED);

		assertThatThrownBy(() -> refund.requireSameRequest(5_000, RefundReason.ORDER_CANCELED))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT.getMessage());
		assertThatThrownBy(() -> refund.requireSameRequest(10_000, RefundReason.ORDER_NOT_PAYABLE))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT.getMessage());
	}

	@DisplayName("환불 금액이 0 이하이면 사건을 열지 못한다")
	@Test
	void open_whenAmountIsNotPositive_throws() {
		assertThatThrownBy(() -> Refund.open(PAYMENT_ID, REFUND_KEY, RefundRequester.MEMBER, "IDEM-1", 0,
			RefundReason.ORDER_CANCELED))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_AMOUNT_INVALID.getMessage());
	}

	@DisplayName("요청 멱등키는 요청자를 가리지 않고 있어야 한다 — 비면 DB가 중복을 막지 못한다")
	@Test
	void open_whenIdempotencyKeyIsBlank_throwsForEveryRequester() {
		assertThatThrownBy(() -> Refund.open(PAYMENT_ID, REFUND_KEY, RefundRequester.MEMBER, " ", 10_000,
			RefundReason.ORDER_CANCELED))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REQUIRED.getMessage());

		assertThatThrownBy(() -> Refund.open(PAYMENT_ID, REFUND_KEY, RefundRequester.SYSTEM, null, 10_000,
			RefundReason.ORDER_NOT_PAYABLE))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REQUIRED.getMessage());
	}

	@DisplayName("사유는 정해진 값으로만 기록되고 회원 취소와 승인 반려가 그 값으로 구분된다")
	@Test
	void open_whenCreatedByEitherPath_recordsPredefinedReason() {
		Refund memberRefund = requestedRefund();
		Refund rejectionRefund = Refund.open(PAYMENT_ID, "RF-ffffffffffffffffffffffffffffffff",
			RefundRequester.SYSTEM, RefundReason.ORDER_NOT_PAYABLE.name(), 10_000,
			RefundReason.ORDER_NOT_PAYABLE);

		assertThat(memberRefund.getReason()).isEqualTo(RefundReason.ORDER_CANCELED);
		assertThat(memberRefund.getRequester()).isEqualTo(RefundRequester.MEMBER);
		assertThat(rejectionRefund.getReason()).isEqualTo(RefundReason.ORDER_NOT_PAYABLE);
		assertThat(rejectionRefund.getRequester()).isEqualTo(RefundRequester.SYSTEM);
	}

	@DisplayName("첫 발송 직전 전이는 시도 번호를 올리고 그 번호에서 호출 멱등키를 새로 파생한다")
	@Test
	void markInProgress_fromRequested_opensFirstAttemptAndDerivesNewPgIdempotencyKey() {
		Refund refund = requestedRefund();
		String beforeKey = refund.getPgIdempotencyKey();

		refund.markInProgress(NOW);

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
		assertThat(refund.getAttemptSeq()).isEqualTo(1);
		assertThat(refund.getLastRequestedAt()).isEqualTo(NOW);
		assertThat(refund.getPgIdempotencyKey())
			.isNotEqualTo(beforeKey)
			.startsWith(REFUND_KEY)
			.endsWith("1");
	}

	@DisplayName("환불 시도 키가 사건 키를 접두어로 갖는다 — 이력에서 우리 시도를 그 접두어로 집는다")
	@Test
	void pgIdempotencyKey_isDerivedFromRefundKey_soHistoryEntriesCanBeMatched() {
		Refund refund = inProgressRefund();

		assertThat(refund.getPgIdempotencyKey()).startsWith(REFUND_KEY);
		assertThat(refund.getPgIdempotencyKey().length()).isLessThanOrEqualTo(64);
	}

	@DisplayName("결제사에 실어 보낼 시도 키를 환불이 스스로 만든다 — 형식의 정본이 이 자리 하나다")
	@Test
	void attemptKey_whenCalled_joinsRefundKeyAndAttemptSeq() {
		Refund refund = requestedRefund();
		assertThat(refund.attemptKey()).isEqualTo(REFUND_KEY + "-0");

		refund.markInProgress(NOW);

		assertThat(refund.attemptKey()).isEqualTo(REFUND_KEY + "-1");
		assertThat(refund.attemptKey()).isEqualTo(refund.getPgIdempotencyKey());
	}

	@DisplayName("이력 항목이 이 사건의 것인지는 이번 시도가 아니라 사건 키 접두어로 가른다")
	@Test
	void ownsHistoryEntry_matchesAnyAttemptOfThisRefund() {
		Refund refund = inProgressRefund();

		assertThat(refund.ownsHistoryEntry(REFUND_KEY + "-1")).isTrue();
		// 지난 시도가 나갔는지를 물어야 하므로 그 사건의 모든 시도가 걸린다.
		assertThat(refund.ownsHistoryEntry(REFUND_KEY + "-7")).isTrue();
		assertThat(refund.ownsHistoryEntry(null)).isFalse();
		assertThat(refund.ownsHistoryEntry("RF-other-1")).isFalse();
		// 구분자까지 붙여 비교해야 사건 키 하나가 다른 사건 키의 앞부분과 같을 때 섞이지 않는다.
		assertThat(refund.ownsHistoryEntry(REFUND_KEY + "9-1")).isFalse();
	}

	@DisplayName("다시 부르기 직전에는 상태와 시도 번호를 그대로 두고 부른 시각만 갱신한다")
	@Test
	void recordRequested_whenCalled_keepsStatusAndKeyAndStampsRequestedAt() {
		Refund refund = inProgressRefund();
		String beforeKey = refund.getPgIdempotencyKey();
		LocalDateTime resentAt = NOW.plusMinutes(3);

		refund.recordRequested(resentAt);

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
		assertThat(refund.getAttemptSeq()).isEqualTo(1);
		assertThat(refund.getPgIdempotencyKey()).isEqualTo(beforeKey);
		assertThat(refund.getLastRequestedAt()).isEqualTo(resentAt);
	}

	@DisplayName("아직 한 번도 안 보낸 환불에는 다시 부르기 직전 전이를 걸 수 없다")
	@Test
	void recordRequested_fromRequested_throws() {
		Refund refund = requestedRefund();

		assertThatThrownBy(() -> refund.recordRequested(NOW))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("응답을 못 받으면 결과 불명이 된다")
	@Test
	void markUnknown_fromInProgress_marksUnknown() {
		Refund refund = inProgressRefund();

		refund.markUnknown();

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
	}

	@DisplayName("결과를 모르던 환불을 이력으로 확정하면 거래 번호가 남고 성공으로 끝난다")
	@Test
	void complete_fromUnknown_recordsPgTransactionId() {
		Refund refund = inProgressRefund();
		refund.markUnknown();

		refund.complete("pg-tx-1");

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(refund.getPgTransactionId()).isEqualTo("pg-tx-1");
	}

	@DisplayName("아직 한 번도 안 보낸 환불을 성공으로 확정할 수는 없다")
	@Test
	void complete_fromRequested_throws() {
		Refund refund = requestedRefund();

		assertThatThrownBy(() -> refund.complete("pg-tx-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("자동으로 더 못 하게 되면 검토 코드가 상태와 함께 채워진다")
	@Test
	void flagForReview_fromInProgress_recordsReviewCodeWithStatus() {
		Refund refund = inProgressRefund();

		refund.flagForReview(RefundReviewCode.CANCEL_DEADLINE_EXPIRED, "취소 기한 초과");

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
		assertThat(refund.getReviewCode()).isEqualTo(RefundReviewCode.CANCEL_DEADLINE_EXPIRED);
		assertThat(refund.getReviewDetail()).isEqualTo("취소 기한 초과");
	}

	@DisplayName("사람이 처리해야 하는 환불을 자동으로 되살리는 길이 없다")
	@Test
	void complete_fromManualReview_throws() {
		Refund refund = inProgressRefund();
		refund.flagForReview(RefundReviewCode.CANCEL_NOT_ALLOWED, "취소 불가");

		assertThatThrownBy(() -> refund.complete("pg-tx-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("성공한 환불은 되돌리지 않는다")
	@Test
	void transition_whenAlreadySucceeded_throws() {
		Refund refund = inProgressRefund();
		refund.complete("pg-tx-1");

		assertThatThrownBy(() -> refund.flagForReview(RefundReviewCode.REQUEST_REJECTED, "값 오류"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("다시 시도할 수 있는 실패를 받으면 상태는 그대로 두고 다음 호출이 새 키로 나가게 한다")
	@Test
	void recordRetryableFailure_whenCalled_keepsStatusAndDerivesNewPgIdempotencyKey() {
		Refund refund = inProgressRefund();
		String beforeKey = refund.getPgIdempotencyKey();

		refund.recordRetryableFailure();

		assertThat(refund.getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
		assertThat(refund.getAttemptSeq()).isEqualTo(2);
		assertThat(refund.getPgIdempotencyKey()).isNotEqualTo(beforeKey);
	}

	@DisplayName("대사가 집을 때마다 회차가 오르고 집은 시각이 남는다")
	@Test
	void recordReconciled_whenCalled_raisesReconcileCountAndStampsPickedAt() {
		Refund refund = inProgressRefund();

		refund.recordReconciled(NOW.plusMinutes(5));

		assertThat(refund.getReconcileCount()).isEqualTo(1);
		assertThat(refund.getLastReconcileAt()).isEqualTo(NOW.plusMinutes(5));
	}

	@DisplayName("통지를 보낸 뒤 알린 시각이 남는다")
	@Test
	void recordNotified_whenCalled_stampsNotifiedAt() {
		Refund refund = inProgressRefund();

		refund.recordNotified(NOW.plusHours(1));

		assertThat(refund.getLastNotifyAt()).isEqualTo(NOW.plusHours(1));
	}

	@DisplayName("결제사를 부른 뒤를 가리키는 이름을 결제와 환불이 같은 뜻으로 쓴다")
	@Test
	void callLifecycleNames_meanTheSameThingForPaymentAndRefund() {
		Payment payment = Payment.start(1L, 1L, PaymentPg.NAVERPAY, "PK-1", "IDEM-1", 10_000);
		Refund refund = requestedRefund();

		// 첫 상태는 양쪽 다 결제사를 아직 부르기 전이다. 기다리는 대상이 달라 이름만 갈린다.
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);

		payment.markInProgress("pg-payment-1", NOW);
		refund.markInProgress(NOW);
		assertThat(payment.getStatus().name()).isEqualTo(refund.getStatus().name());

		payment.markUnknown();
		refund.markUnknown();
		assertThat(payment.getStatus().name()).isEqualTo(refund.getStatus().name());
	}
}
