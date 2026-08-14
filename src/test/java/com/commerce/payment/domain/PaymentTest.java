package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

class PaymentTest {

	private static final Long ORDER_ID = 100L;
	private static final Long MEMBER_ID = 7L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	private Payment readyPayment() {
		return Payment.start(ORDER_ID, MEMBER_ID, PaymentPg.NAVERPAY, "PK-1", "IDEM-1", 10_000);
	}

	private Payment inProgressPayment() {
		Payment payment = readyPayment();
		payment.markInProgress("pg-payment-1", NOW);
		return payment;
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

		assertThatThrownBy(() -> payment.reject(PaymentCloseCode.AMOUNT_MISMATCH, "금액 불일치", 10_000, "pg-tx-1"))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED.getMessage());
	}

	@DisplayName("반려로 종결하면 되돌릴 금액이 남고 활성 슬롯을 반납한다")
	@Test
	void reject_fromInProgress_recordsApprovedAmountAndReleasesActiveSlot() {
		Payment payment = inProgressPayment();

		payment.reject(PaymentCloseCode.ORDER_NOT_PAYABLE, "이미 취소된 주문", 10_000, "pg-tx-1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
		assertThat(payment.getApprovedAmount()).isEqualTo(10_000);
		assertThat(payment.getActiveOrderKey()).isNull();
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
		rejected.reject(PaymentCloseCode.AMOUNT_MISMATCH, "금액 불일치", 10_000, "pg-tx-1");
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
