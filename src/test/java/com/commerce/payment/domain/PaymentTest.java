package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

class PaymentTest {

	@DisplayName("APPROVE 타입 승인 성공 처리 시 상태가 SUCCEEDED가 되고 approvedOrderKey에 orderId가 설정된다")
	@Test
	void succeed_whenApproveType_updateStatusAndSetApprovedOrderKey() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		payment.succeed(respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(payment.getApprovedOrderKey()).isEqualTo(1L);
		assertThat(payment.getFailCode()).isNull();
		assertThat(payment.getFailDetail()).isNull();
	}

	@DisplayName("CANCEL 타입 승인 성공 처리 시 상태가 SUCCEEDED가 되고 approvedOrderKey는 변경되지 않는다")
	@Test
	void succeed_whenCancelType_updateStatusWithoutApprovedOrderKey() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		cancelPayment.succeed(respondedAt);

		// then
		assertThat(cancelPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(cancelPayment.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(cancelPayment.getApprovedOrderKey()).isNull();
	}

	@DisplayName("실패 처리 시 실패 사유와 응답 시간이 저장된다")
	@Test
	void fail_whenCalled_updateFailedState() {
		// given
		Payment payment = Payment.createCancelRequested(1L, "PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 20);

		// when
		payment.fail(PaymentFailCode.PG_REQUEST_REJECTED, "cancel failed", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo(PaymentFailCode.PG_REQUEST_REJECTED);
		assertThat(payment.getFailDetail()).isEqualTo("cancel failed");
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("markUnknown 호출 시 상태가 UNKNOWN이 되고 failDetail과 respondedAt이 저장된다")
	@Test
	void markUnknown_whenCalled_updateStatusToUnknown() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 30);

		// when
		payment.markUnknown("PG 응답 타임아웃", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getFailDetail()).isEqualTo("PG 응답 타임아웃");
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(payment.getApprovedOrderKey()).isNull();
	}

	@DisplayName("markUnknown을 REQUESTED 외 상태에서 호출 시 예외가 발생한다")
	@Test
	void markUnknown_whenStatusNotRequested_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> payment.markUnknown("detail", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("이미 SUCCEEDED 상태인 payment에 succeed 호출 시 예외가 발생한다")
	@Test
	void succeed_whenStatusSucceeded_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> payment.succeed(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("이미 FAILED 상태인 payment에 fail 호출 시 예외가 발생한다")
	@Test
	void fail_whenStatusFailed_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.fail(PaymentFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> payment.fail(PaymentFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("UNKNOWN 상태에서 succeed 호출 시 SUCCEEDED로 전이된다")
	@Test
	void succeed_whenStatusUnknown_updateStatusToSucceeded() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.markUnknown("PG 응답 타임아웃", LocalDateTime.now());
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		payment.succeed(respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(payment.getApprovedOrderKey()).isEqualTo(1L);
	}

	@DisplayName("UNKNOWN 상태에서 fail 호출 시 FAILED로 전이된다")
	@Test
	void fail_whenStatusUnknown_updateStatusToFailed() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.markUnknown("PG 응답 타임아웃", LocalDateTime.now());
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 20);

		// when
		payment.fail(PaymentFailCode.PG_REQUEST_REJECTED, "PG 확정 실패", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo(PaymentFailCode.PG_REQUEST_REJECTED);
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("REQUESTED 상태에서 markManualReview 호출 시 MANUAL_REVIEW로 전이된다")
	@Test
	void markManualReview_whenStatusRequested_updateStatusToManualReview() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		LocalDateTime now = LocalDateTime.of(2026, 3, 5, 20, 30);

		// when
		payment.markManualReview("자동 처리 상한 초과", now);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW);
		assertThat(payment.getFailDetail()).isEqualTo("자동 처리 상한 초과");
		assertThat(payment.getRespondedAt()).isEqualTo(now);
	}

	@DisplayName("UNKNOWN 상태에서 markManualReview 호출 시 MANUAL_REVIEW로 전이된다")
	@Test
	void markManualReview_whenStatusUnknown_updateStatusToManualReview() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.markUnknown("PG 응답 타임아웃", LocalDateTime.now());
		LocalDateTime now = LocalDateTime.of(2026, 3, 5, 20, 40);

		// when
		payment.markManualReview("6시간 초과", now);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW);
		assertThat(payment.getFailDetail()).isEqualTo("6시간 초과");
		assertThat(payment.getRespondedAt()).isEqualTo(now);
	}

	@DisplayName("이미 MANUAL_REVIEW 상태에서 markManualReview 호출 시 멱등으로 예외가 발생하지 않는다")
	@Test
	void markManualReview_whenAlreadyManualReview_isIdempotent() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		LocalDateTime first = LocalDateTime.of(2026, 3, 5, 20, 30);
		payment.markManualReview("1차 승급", first);

		// when & then
		assertThatCode(() -> payment.markManualReview("2차 시도", LocalDateTime.of(2026, 3, 5, 21, 0)))
			.doesNotThrowAnyException();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW);
		// 첫 번째 호출 값 보존
		assertThat(payment.getFailDetail()).isEqualTo("1차 승급");
		assertThat(payment.getRespondedAt()).isEqualTo(first);
	}

	@DisplayName("SUCCEEDED 상태에서 markManualReview 호출 시 예외가 발생한다")
	@Test
	void markManualReview_whenStatusSucceeded_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		payment.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> payment.markManualReview("이미 성공", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("merchantPayKey와 금액이 모두 일치하면 verifyApprovedResponse에서 예외가 발생하지 않는다")
	@Test
	void verifyApprovedResponse_whenBothMatch_noException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");

		// when & then
		assertThatCode(() -> payment.verifyApprovedResponse("PAY-1", 1000)).doesNotThrowAnyException();
	}

	@DisplayName("merchantPayKey가 다르면 verifyApprovedResponse에서 PAYMENT_MERCHANT_KEY_MISMATCH를 던진다")
	@Test
	void verifyApprovedResponse_whenMerchantPayKeyMismatch_throwPaymentMerchantKeyMismatch() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");

		// when & then
		assertThatThrownBy(() -> payment.verifyApprovedResponse("OTHER-PAY", 1000))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));
	}

	@DisplayName("금액이 다르면 verifyApprovedResponse에서 PAYMENT_AMOUNT_MISMATCH를 던진다")
	@Test
	void verifyApprovedResponse_whenAmountMismatch_throwPaymentAmountMismatch() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");

		// when & then
		assertThatThrownBy(() -> payment.verifyApprovedResponse("PAY-1", 2000))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
	}
}
