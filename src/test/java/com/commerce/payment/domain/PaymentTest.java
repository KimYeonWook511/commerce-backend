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
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		attempt.succeed(respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(attempt.getApprovedOrderKey()).isEqualTo(1L);
		assertThat(attempt.getFailCode()).isNull();
		assertThat(attempt.getFailDetail()).isNull();
	}

	@DisplayName("CANCEL 타입 승인 성공 처리 시 상태가 SUCCEEDED가 되고 approvedOrderKey는 변경되지 않는다")
	@Test
	void succeed_whenCancelType_updateStatusWithoutApprovedOrderKey() {
		// given
		Payment cancelAttempt = Payment.createCancelRequested(1L, "PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		cancelAttempt.succeed(respondedAt);

		// then
		assertThat(cancelAttempt.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(cancelAttempt.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(cancelAttempt.getApprovedOrderKey()).isNull();
	}

	@DisplayName("실패 처리 시 실패 사유와 응답 시간이 저장된다")
	@Test
	void fail_whenCalled_updateFailedState() {
		// given
		Payment attempt = Payment.createCancelRequested(1L, "PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 20);

		// when
		attempt.fail(PaymentFailCode.PG_REQUEST_REJECTED, "cancel failed", respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(attempt.getFailCode()).isEqualTo(PaymentFailCode.PG_REQUEST_REJECTED);
		assertThat(attempt.getFailDetail()).isEqualTo("cancel failed");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("markUnknown 호출 시 상태가 UNKNOWN이 되고 failDetail과 respondedAt이 저장된다")
	@Test
	void markUnknown_whenCalled_updateStatusToUnknown() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 30);

		// when
		attempt.markUnknown("PG 응답 타임아웃", respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(attempt.getFailDetail()).isEqualTo("PG 응답 타임아웃");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(attempt.getApprovedOrderKey()).isNull();
	}

	@DisplayName("markUnknown을 REQUESTED 외 상태에서 호출 시 예외가 발생한다")
	@Test
	void markUnknown_whenStatusNotRequested_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		attempt.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.markUnknown("detail", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("이미 SUCCEEDED 상태인 payment에 succeed 호출 시 예외가 발생한다")
	@Test
	void succeed_whenStatusSucceeded_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		attempt.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.succeed(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("이미 FAILED 상태인 payment에 fail 호출 시 예외가 발생한다")
	@Test
	void fail_whenStatusFailed_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");
		attempt.fail(PaymentFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.fail(PaymentFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("merchantPayKey와 금액이 모두 일치하면 verifyApprovedResponse에서 예외가 발생하지 않는다")
	@Test
	void verifyApprovedResponse_whenBothMatch_noException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");

		// when & then
		assertThatCode(() -> attempt.verifyApprovedResponse("PAY-1", 1000)).doesNotThrowAnyException();
	}

	@DisplayName("merchantPayKey가 다르면 verifyApprovedResponse에서 PAYMENT_MERCHANT_KEY_MISMATCH를 던진다")
	@Test
	void verifyApprovedResponse_whenMerchantPayKeyMismatch_throwPaymentMerchantKeyMismatch() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");

		// when & then
		assertThatThrownBy(() -> attempt.verifyApprovedResponse("OTHER-PAY", 1000))
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
		Payment attempt = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id");

		// when & then
		assertThatThrownBy(() -> attempt.verifyApprovedResponse("PAY-1", 2000))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
	}
}
