package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

class PaymentTest {

	private static final Long ORDER_ID = 100L;
	private static final Long MEMBER_ID = 7L;
	private static final Long PAYMENT_ID = 55L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	private Payment readyPayment() {
		return Payment.start(ORDER_ID, MEMBER_ID, PaymentPg.NAVERPAY, "PK-1", "IDEM-1", 10_000);
	}

	private Payment inProgressPayment() {
		Payment payment = readyPayment();
		payment.markInProgress("pg-payment-1", NOW);
		return payment;
	}

	/** 환불은 결제를 식별자로 참조하므로 소유 대조가 성립하려면 저장된 결제처럼 그 값이 있어야 한다 */
	private Payment identified(Payment payment, Long id) {
		ReflectionTestUtils.setField(payment, "id", id);
		return payment;
	}

	private Payment approvedPayment(int approvedAmount) {
		Payment payment = inProgressPayment();
		payment.succeed(approvedAmount, "pg-tx-1");
		return identified(payment, PAYMENT_ID);
	}

	@DisplayName("결제를 시작하면 승인 호출 전 상태가 되고 그 자리에서 활성 슬롯을 잡는다")
	@Test
	void start_whenCalled_createsReadyPaymentHoldingActiveSlot() {
		Payment payment = readyPayment();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(payment.getActiveOrderKey()).isEqualTo(ORDER_ID);
		assertThat(payment.getAttemptSeq()).isZero();
		assertThat(payment.getReconcileCount()).isZero();
		assertThat(payment.getTotalRefundedAmount()).isZero();
		assertThat(payment.getApprovedAmount()).isNull();
	}

	@DisplayName("승인 호출 직전 전이는 결제사 번호를 심고 첫 시도 번호를 올리며 부른 시각을 남긴다")
	@Test
	void markInProgress_fromReady_recordsPgPaymentIdAndFirstAttempt() {
		Payment payment = readyPayment();

		payment.markInProgress("pg-payment-1", NOW);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(payment.getPgPaymentId()).isEqualTo("pg-payment-1");
		assertThat(payment.getAttemptSeq()).isEqualTo(1);
		assertThat(payment.getLastRequestedAt()).isEqualTo(NOW);
		assertThat(payment.getActiveOrderKey()).isEqualTo(ORDER_ID);
	}

	@DisplayName("이미 승인을 부른 결제에 승인 호출 직전 전이를 다시 걸면 막힌다")
	@Test
	void markInProgress_whenAlreadyCalled_throws() {
		Payment payment = inProgressPayment();

		assertThatThrownBy(() -> payment.markInProgress("pg-payment-2", NOW))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("승인이 확정되면 승인 금액과 거래 번호가 남고 성공한 결제는 활성 슬롯을 계속 쥔다")
	@Test
	void succeed_fromInProgress_recordsApprovalAndKeepsActiveSlot() {
		Payment payment = inProgressPayment();

		payment.succeed(10_000, "pg-tx-1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getApprovedAmount()).isEqualTo(10_000);
		assertThat(payment.getPgTransactionId()).isEqualTo("pg-tx-1");
		assertThat(payment.getActiveOrderKey()).isEqualTo(ORDER_ID);
	}

	@DisplayName("승인 금액이 0이면 확정하지 못한다 — 한도가 처음부터 0이라 되돌릴 환불을 만들 수 없다")
	@Test
	void succeed_whenApprovedAmountIsZero_throwsAndLeavesPaymentUnconfirmed() {
		Payment payment = inProgressPayment();

		assertThatThrownBy(() -> payment.succeed(0, "pg-tx-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_APPROVED_AMOUNT_INVALID.getMessage());
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(payment.getApprovedAmount()).isNull();
	}

	@DisplayName("승인을 부른 뒤 실패로 종결하면 종결 코드가 남고 활성 슬롯을 반납한다")
	@Test
	void fail_fromInProgress_recordsCloseCodeAndReleasesActiveSlot() {
		Payment payment = inProgressPayment();

		payment.fail(PaymentCloseCode.PG_DECLINED, "잔액 부족");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.PG_DECLINED);
		assertThat(payment.getCloseDetail()).isEqualTo("잔액 부족");
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	@DisplayName("승인을 부르지 않았는데 실패로 종결할 수는 없다")
	@Test
	void fail_fromReady_throws() {
		Payment payment = readyPayment();

		assertThatThrownBy(() -> payment.fail(PaymentCloseCode.PG_DECLINED, "잔액 부족"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("받아들일 승인이 아직 없는 결제를 반려할 수는 없다")
	@Test
	void reject_fromReady_throws() {
		Payment payment = readyPayment();

		assertThatThrownBy(() -> payment.reject(PaymentCloseCode.AMOUNT_MISMATCH, "금액 불일치"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("반려로 종결하면 되돌릴 금액이 남고 활성 슬롯을 반납한다")
	@Test
	void reject_fromInProgress_recordsApprovedAmountAndReleasesActiveSlot() {
		Payment payment = inProgressPayment();
		payment.recordApproval(10_000, "pg-tx-1");

		boolean closed = payment.reject(PaymentCloseCode.ORDER_NOT_PAYABLE, "이미 취소된 주문");

		assertThat(closed).isTrue();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
		assertThat(payment.getApprovedAmount()).isEqualTo(10_000);
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	@DisplayName("이미 종착인 결제를 반려하면 전이를 건너뛰고 그 사실을 알려 준다")
	@Test
	void reject_whenAlreadyClosed_skipsTransitionAndReportsIt() {
		Payment payment = inProgressPayment();
		payment.succeed(10_000, "pg-tx-1");

		boolean closed = payment.reject(PaymentCloseCode.ORDER_NOT_PAYABLE, "이미 취소된 주문");

		// 전이가 안 됐다고 되돌릴 근거까지 롤백하면 이미 나간 돈이 그대로 남는다.
		assertThat(closed).isFalse();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("밖에서 이미 취소된 승인을 발견해 종결하면 승인이 났었다는 사실이 그 행에 남는다")
	@Test
	void failExternallyCanceled_fromUnknown_keepsApprovedAmountAndReleasesActiveSlot() {
		Payment payment = inProgressPayment();
		payment.markUnknown();

		payment.failExternallyCanceled("외부 취소 확인", 10_000, "pg-tx-1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.EXTERNALLY_CANCELED);
		assertThat(payment.getApprovedAmount()).isEqualTo(10_000);
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	@DisplayName("승인을 부르지 않은 채 만료되면 활성 슬롯을 반납한다")
	@Test
	void expire_fromReady_releasesActiveSlot() {
		Payment payment = readyPayment();

		payment.expire(PaymentCloseCode.SUPERSEDED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.SUPERSEDED);
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	@DisplayName("승인을 이미 부른 결제는 만료로 종결하지 못한다 — 그 승인이 성공이면 한 주문에 승인이 둘 성립한다")
	@Test
	void expire_whenApprovalAlreadyCalled_throws() {
		Payment payment = inProgressPayment();

		assertThatThrownBy(() -> payment.expire(PaymentCloseCode.SESSION_TIMEOUT))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("그 종결 상태의 것이 아닌 종결 코드는 받지 않는다")
	@Test
	void fail_withCloseCodeOfAnotherTerminalStatus_throws() {
		Payment payment = inProgressPayment();

		assertThatThrownBy(() -> payment.fail(PaymentCloseCode.SESSION_TIMEOUT, "만료"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_CLOSE_CODE_NOT_ALLOWED.getMessage());
	}

	@DisplayName("응답을 못 받으면 결과 불명이 되고 활성 슬롯은 그대로 묶여 있다")
	@Test
	void markUnknown_fromInProgress_keepsActiveSlot() {
		Payment payment = inProgressPayment();

		payment.markUnknown();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getActiveOrderKey()).isEqualTo(ORDER_ID);
	}

	@DisplayName("남의 승인 응답으로 드러난 결제를 회수하면 결제사 번호가 심기고 결과 불명으로 남는다")
	@Test
	void reclaim_fromReady_recordsPgPaymentIdAndMarksUnknown() {
		Payment payment = readyPayment();

		payment.reclaim("pg-payment-other");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getPgPaymentId()).isEqualTo("pg-payment-other");
		assertThat(payment.getActiveOrderKey()).isEqualTo(ORDER_ID);
	}

	@DisplayName("승인을 이미 부른 결제는 회수하지 않는다 — 그 행은 자기 경로로 풀린다")
	@Test
	void reclaim_whenApprovalAlreadyCalled_throws() {
		Payment payment = inProgressPayment();

		assertThatThrownBy(() -> payment.reclaim("pg-payment-other"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("다시 시도할 수 있는 실패를 받으면 상태는 그대로 두고 시도 번호만 오른다")
	@Test
	void recordRetryableFailure_whenCalled_raisesAttemptSeqAndKeepsStatus() {
		Payment payment = inProgressPayment();

		payment.recordRetryableFailure();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(payment.getAttemptSeq()).isEqualTo(2);
	}

	@DisplayName("결제사 호출 멱등키는 결제 키에 시도 번호를 붙인 값이고 시도 번호가 오를 때만 바뀐다")
	@Test
	void pgIdempotencyKey_whenAttemptSeqRaised_derivesNewKey() {
		Payment payment = inProgressPayment();

		String firstAttemptKey = payment.pgIdempotencyKey();
		payment.recordReconciled(NOW.plusMinutes(1));
		String sameAttemptKey = payment.pgIdempotencyKey();
		payment.recordRetryableFailure();
		String nextAttemptKey = payment.pgIdempotencyKey();

		assertThat(firstAttemptKey).isEqualTo("PK-1-1");
		assertThat(sameAttemptKey).isEqualTo(firstAttemptKey);
		assertThat(nextAttemptKey).isEqualTo("PK-1-2");
	}

	@DisplayName("대사가 집을 때마다 회차가 오르고 집은 시각이 남는다")
	@Test
	void recordReconciled_whenCalled_raisesReconcileCountAndStampsPickedAt() {
		Payment payment = inProgressPayment();

		payment.recordReconciled(NOW.plusMinutes(5));
		payment.recordReconciled(NOW.plusMinutes(10));

		assertThat(payment.getReconcileCount()).isEqualTo(2);
		assertThat(payment.getLastReconcileAt()).isEqualTo(NOW.plusMinutes(10));
	}

	@DisplayName("통지를 보낸 뒤 알린 시각이 남는다")
	@Test
	void recordNotified_whenCalled_stampsNotifiedAt() {
		Payment payment = inProgressPayment();

		payment.recordNotified(NOW.plusHours(1));

		assertThat(payment.getLastNotifyAt()).isEqualTo(NOW.plusHours(1));
	}

	@DisplayName("종착 상태가 된 결제는 다시 전이시키지 못한다")
	@Test
	void transition_whenAlreadyTerminal_throws() {
		Payment payment = inProgressPayment();
		payment.succeed(10_000, "pg-tx-1");

		assertThatThrownBy(() -> payment.fail(PaymentCloseCode.PG_DECLINED, "거절"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("같은 멱등키로 다시 온 요청이 이 결제를 만든 요청과 같으면 통과한다")
	@Test
	void requireSameRequest_whenOrderAndAmountMatch_passes() {
		Payment payment = readyPayment();

		payment.requireSameRequest(ORDER_ID, 10_000);
	}

	@DisplayName("같은 멱등키에 다른 주문이 실려 오면 앞서 만든 결제를 돌려주지 않고 거절한다")
	@Test
	void requireSameRequest_whenOrderDiffers_throws() {
		Payment payment = readyPayment();

		assertThatThrownBy(() -> payment.requireSameRequest(ORDER_ID + 1, 10_000))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_IDEMPOTENCY_KEY_CONFLICT.getMessage());
	}

	@DisplayName("같은 멱등키에 다른 금액이 실려 오면 앞서 만든 결제를 돌려주지 않고 거절한다")
	@Test
	void requireSameRequest_whenAmountDiffers_throws() {
		Payment payment = readyPayment();

		assertThatThrownBy(() -> payment.requireSameRequest(ORDER_ID, 20_000))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_IDEMPOTENCY_KEY_CONFLICT.getMessage());
	}

	// ── 환불 생성과 한도 판정 ────────────────────────────────────

	@DisplayName("환불을 만들면 결제 행의 환불 합이 그 금액만큼 오른다")
	@Test
	void openRefund_whenNoExistingRefund_createsRefundAndRaisesTotal() {
		Payment payment = approvedPayment(10_000);

		Refund refund = payment.openRefund(Optional.empty(), 3_000, RefundReason.ORDER_CANCELED, "IDEM-refund");

		assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(refund.getAmount()).isEqualTo(3_000);
		assertThat(refund.getRequester()).isEqualTo(RefundRequester.MEMBER);
		assertThat(refund.getIdempotencyKey()).isEqualTo("IDEM-refund");
		assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
		// 시도 번호를 붙여도 결제사 한도 안에 들도록 길이를 고정한다.
		assertThat(refund.getRefundKey()).startsWith("RF-").hasSize(35);
		// 이 갱신이 없으면 동시에 온 두 요청이 서로를 감지하지 못한다.
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(3_000);
	}

	@DisplayName("한 결제에 환불이 여러 건 쌓이고 각각 다른 사건 키로 구분된다")
	@Test
	void openRefund_whenCalledTwice_stacksRefundsWithDistinctKeys() {
		Payment payment = approvedPayment(10_000);

		Refund first = payment.openRefund(Optional.empty(), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1");
		Refund second = payment.openRefund(Optional.empty(), 4_000, RefundReason.ORDER_CANCELED, "IDEM-2");

		assertThat(first.getRefundKey()).isNotEqualTo(second.getRefundKey());
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(7_000);
	}

	@DisplayName("남은 한도를 넘는 환불 요청은 거절되고 결제 행의 환불 합도 그대로다")
	@Test
	void openRefund_whenAmountExceedsRemainingLimit_throws() {
		Payment payment = approvedPayment(10_000);
		payment.openRefund(Optional.empty(), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		assertThatThrownBy(() ->
			payment.openRefund(Optional.empty(), 8_000, RefundReason.ORDER_CANCELED, "IDEM-2"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_LIMIT_EXCEEDED.getMessage());

		assertThat(payment.getTotalRefundedAmount()).isEqualTo(3_000);
	}

	@DisplayName("결과를 모르는 환불도 한도를 잡고 있어 남은 금액을 넘는 요청이 거절된다")
	@Test
	void openRefund_whenPendingRefundOccupiesLimit_throws() {
		Payment payment = approvedPayment(10_000);
		Refund pending = payment.openRefund(Optional.empty(), 6_000, RefundReason.ORDER_CANCELED, "IDEM-1");
		pending.markInProgress(NOW);
		pending.markUnknown();

		assertThatThrownBy(() ->
			payment.openRefund(Optional.empty(), 5_000, RefundReason.ORDER_CANCELED, "IDEM-2"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_LIMIT_EXCEEDED.getMessage());
	}

	@DisplayName("자동 처리가 멈춘 환불도 한도를 잡고 있다")
	@Test
	void openRefund_whenManualReviewRefundOccupiesLimit_throws() {
		Payment payment = approvedPayment(10_000);
		Refund stuck = payment.openRefund(Optional.empty(), 6_000, RefundReason.ORDER_CANCELED, "IDEM-1");
		stuck.markInProgress(NOW);
		stuck.flagForReview(RefundReviewCode.CANCEL_NOT_ALLOWED, "취소 불가");

		// 상태로 예외를 두면 아직 돌려주지도 않은 금액만큼 새 환불이 끼어든다.
		assertThatThrownBy(() ->
			payment.openRefund(Optional.empty(), 8_000, RefundReason.ORDER_CANCELED, "IDEM-2"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_LIMIT_EXCEEDED.getMessage());
	}

	@DisplayName("전액이 이미 환불된 결제에는 금액이 얼마든 더 환불할 수 없다")
	@Test
	void openRefund_whenFullyRefunded_throws() {
		Payment payment = approvedPayment(10_000);
		payment.openRefund(Optional.empty(), 10_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		assertThatThrownBy(() ->
			payment.openRefund(Optional.empty(), 1, RefundReason.ORDER_CANCELED, "IDEM-2"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_LIMIT_EXCEEDED.getMessage());
	}

	@DisplayName("환불 금액이 0 이하이면 사건이 생기지 않는다")
	@Test
	void openRefund_whenAmountIsNotPositive_throws() {
		Payment payment = approvedPayment(10_000);

		assertThatThrownBy(() ->
			payment.openRefund(Optional.empty(), 0, RefundReason.ORDER_CANCELED, "IDEM-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_AMOUNT_INVALID.getMessage());

		assertThat(payment.getTotalRefundedAmount()).isZero();
	}

	@DisplayName("승인 금액이 없는 결제에는 환불을 만들 수 없다")
	@Test
	void openRefund_whenApprovedAmountIsMissing_throws() {
		Payment payment = identified(inProgressPayment(), PAYMENT_ID);

		// 얼마를 돌려줘야 하는지가 정해지지 않았다.
		assertThatThrownBy(() ->
			payment.openRefund(Optional.empty(), 1_000, RefundReason.ORDER_CANCELED, "IDEM-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_APPROVED_AMOUNT_MISSING.getMessage());
	}

	@DisplayName("같은 요청 키로 같은 내용이 다시 오면 앞서 만든 사건이 그대로 돌아온다")
	@Test
	void openRefund_whenSameRequestRepeats_returnsExistingWithoutRaisingTotal() {
		Payment payment = approvedPayment(10_000);
		Refund existing = payment.openRefund(Optional.empty(), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		Refund again = payment.openRefund(
			Optional.of(existing), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		assertThat(again).isSameAs(existing);
		// 돌려주는 것뿐이라 누적 환불액을 다시 더하지 않는다.
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(3_000);
	}

	@DisplayName("같은 요청 키에 금액이 다르면 앞서 만든 사건을 돌려주지 않고 거절한다")
	@Test
	void openRefund_whenSameKeyCarriesDifferentAmount_throws() {
		Payment payment = approvedPayment(10_000);
		Refund existing = payment.openRefund(Optional.empty(), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		// 돌려주면 요청한 5,000원 환불이 실행되지 않았는데 성공 응답이 나간다.
		assertThatThrownBy(() ->
			payment.openRefund(Optional.of(existing), 5_000, RefundReason.ORDER_CANCELED, "IDEM-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT.getMessage());
	}

	@DisplayName("다른 결제의 환불을 넘겨받으면 그것을 이번 요청의 결과로 돌려주지 않는다")
	@Test
	void openRefund_whenExistingBelongsToAnotherPayment_throws() {
		Payment payment = approvedPayment(10_000);
		Payment other = identified(approvedPayment(10_000), PAYMENT_ID + 1);
		Refund foreign = other.openRefund(Optional.empty(), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		assertThatThrownBy(() ->
			payment.openRefund(Optional.of(foreign), 3_000, RefundReason.ORDER_CANCELED, "IDEM-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_NOT_OWNED_BY_PAYMENT.getMessage());
	}

	// ── 승인 반려가 여는 환불 ────────────────────────────────────

	@DisplayName("반려 환불은 남은 한도만큼 시스템 요청으로 만들어지고 요청 키에 사유가 담긴다")
	@Test
	void openRejectionRefund_whenNoExistingRefund_createsSystemRefundForRemainingLimit() {
		Payment payment = approvedPayment(10_000);

		Optional<Refund> refund = payment.openRejectionRefund(Optional.empty(), RefundReason.ORDER_NOT_PAYABLE);

		assertThat(refund).isPresent();
		assertThat(refund.get().getAmount()).isEqualTo(10_000);
		assertThat(refund.get().getRequester()).isEqualTo(RefundRequester.SYSTEM);
		// 비워 두면 유일 검사에서 빠져 DB가 중복을 막지 못한다.
		assertThat(refund.get().getIdempotencyKey()).isEqualTo(RefundReason.ORDER_NOT_PAYABLE.name());
		assertThat(refund.get().getReason()).isEqualTo(RefundReason.ORDER_NOT_PAYABLE);
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(10_000);
	}

	@DisplayName("반려 환불이 이미 있으면 금액을 다시 계산하지 않고 그것을 돌려준다")
	@Test
	void openRejectionRefund_whenSystemRefundExists_returnsItWithoutRecalculating() {
		Payment payment = approvedPayment(10_000);
		Refund existing = payment.openRejectionRefund(
			Optional.empty(), RefundReason.ORDER_NOT_PAYABLE).orElseThrow();

		Optional<Refund> again = payment.openRejectionRefund(
			Optional.of(existing), RefundReason.ORDER_NOT_PAYABLE);

		// 다시 계산하면 그 환불이 이미 한도를 잡고 있어 남은 한도가 0이 된다.
		assertThat(again).containsSame(existing);
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(10_000);
	}

	@DisplayName("한도가 모자란 채로 반려가 와도 남은 만큼은 되돌릴 근거로 남는다")
	@Test
	void openRejectionRefund_whenLimitIsPartlyTaken_createsRefundForWhatIsLeft() {
		Payment payment = approvedPayment(10_000);
		payment.openRefund(Optional.empty(), 4_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		Optional<Refund> refund = payment.openRejectionRefund(Optional.empty(), RefundReason.AMOUNT_MISMATCH);

		// 승인 금액으로 고정하면 한도를 넘어 반려가 통째로 막힌다.
		assertThat(refund).isPresent();
		assertThat(refund.get().getAmount()).isEqualTo(6_000);
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(10_000);
	}

	@DisplayName("남은 한도가 0이면 반려 환불을 만들지 않고 비어 있는 결과를 돌려준다")
	@Test
	void openRejectionRefund_whenNoLimitIsLeft_returnsEmpty() {
		Payment payment = approvedPayment(10_000);
		payment.openRefund(Optional.empty(), 10_000, RefundReason.ORDER_CANCELED, "IDEM-1");

		Optional<Refund> refund = payment.openRejectionRefund(Optional.empty(), RefundReason.AMOUNT_MISMATCH);

		// 예외로 터뜨리면 반려가 통째로 롤백되어 되돌릴 근거도 조사할 근거도 사라진다.
		assertThat(refund).isEmpty();
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(10_000);
	}

	@DisplayName("어느 전이를 거치든 상태와 활성 슬롯이 어긋나지 않는다")
	@Test
	void everyTransition_keepsActiveSlotConsistentWithStatus() {
		assertSlotMatchesStatus(readyPayment());

		Payment inProgress = inProgressPayment();
		assertSlotMatchesStatus(inProgress);

		Payment unknown = inProgressPayment();
		unknown.markUnknown();
		assertSlotMatchesStatus(unknown);

		Payment reclaimed = readyPayment();
		reclaimed.reclaim("pg-payment-other");
		assertSlotMatchesStatus(reclaimed);

		Payment succeeded = inProgressPayment();
		succeeded.succeed(10_000, "pg-tx-1");
		assertSlotMatchesStatus(succeeded);

		Payment failed = inProgressPayment();
		failed.fail(PaymentCloseCode.PG_DECLINED, "거절");
		assertSlotMatchesStatus(failed);

		Payment externallyCanceled = inProgressPayment();
		externallyCanceled.failExternallyCanceled("외부 취소", 10_000, "pg-tx-1");
		assertSlotMatchesStatus(externallyCanceled);

		Payment rejected = inProgressPayment();
		rejected.recordApproval(10_000, "pg-tx-1");
		rejected.reject(PaymentCloseCode.AMOUNT_MISMATCH, "금액 불일치");
		assertSlotMatchesStatus(rejected);

		Payment expired = readyPayment();
		expired.expire(PaymentCloseCode.SESSION_TIMEOUT);
		assertSlotMatchesStatus(expired);
	}

	private void assertSlotMatchesStatus(Payment payment) {
		if (payment.getStatus().holdsActiveSlot()) {
			assertThat(payment.getActiveOrderKey())
				.as("%s 는 활성 슬롯을 쥔다", payment.getStatus())
				.isEqualTo(ORDER_ID);
		} else {
			assertThat(payment.getActiveOrderKey())
				.as("%s 는 활성 슬롯을 반납한다", payment.getStatus())
				.isNull();
		}
	}
}
