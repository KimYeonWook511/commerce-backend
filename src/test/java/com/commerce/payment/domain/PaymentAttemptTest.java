package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

class PaymentAttemptTest {

	@DisplayName("승인 성공 처리 시 상태와 응답 시간이 갱신된다")
	@Test
	void markApproveSucceeded_whenCalled_updateStatusAndRespondedAt() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		attempt.markApproveSucceeded(respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(attempt.getFailCode()).isNull();
		assertThat(attempt.getFailDetail()).isNull();
	}

	@DisplayName("취소 실패 처리 시 실패 사유와 응답 시간이 저장된다")
	@Test
	void markCancelFailed_whenCalled_updateFailedState() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 20);

		// when
		attempt.markCancelFailed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "cancel failed", respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getFailCode()).isEqualTo(PaymentAttemptFailCode.PG_REQUEST_REJECTED);
		assertThat(attempt.getFailDetail()).isEqualTo("cancel failed");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("이미 SUCCEEDED 상태인 attempt에 markApproveSucceeded 호출 시 예외가 발생한다")
	@Test
	void markApproveSucceeded_whenStatusSucceeded_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.markApproveSucceeded(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.markApproveSucceeded(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("이미 FAILED 상태인 attempt에 markApproveSucceeded 호출 시 예외가 발생한다")
	@Test
	void markApproveSucceeded_whenStatusFailed_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.markApproveFailed(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.markApproveSucceeded(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("CANCEL type attempt에 markApproveSucceeded 호출 시 예외가 발생한다")
	@Test
	void markApproveSucceeded_whenTypeIsCancel_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

		// when & then
		assertThatThrownBy(() -> attempt.markApproveSucceeded(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_TYPE_MISMATCH));
	}

	@DisplayName("REQUESTED 상태가 아닌 attempt에 markApproveFailed 호출 시 예외가 발생한다")
	@Test
	void markApproveFailed_whenStatusNotRequested_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.markApproveSucceeded(LocalDateTime.now());

		// when & then
		assertThatThrownBy(
			() -> attempt.markApproveFailed(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("CANCEL type attempt에 markApproveFailed 호출 시 예외가 발생한다")
	@Test
	void markApproveFailed_whenTypeIsCancel_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

		// when & then
		assertThatThrownBy(
			() -> attempt.markApproveFailed(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_TYPE_MISMATCH));
	}

	@DisplayName("REQUESTED 상태가 아닌 attempt에 markCancelSucceeded 호출 시 예외가 발생한다")
	@Test
	void markCancelSucceeded_whenStatusNotRequested_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.markCancelSucceeded(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.markCancelSucceeded(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("APPROVE type attempt에 markCancelSucceeded 호출 시 예외가 발생한다")
	@Test
	void markCancelSucceeded_whenTypeIsApprove_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

		// when & then
		assertThatThrownBy(() -> attempt.markCancelSucceeded(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_TYPE_MISMATCH));
	}

	@DisplayName("REQUESTED 상태가 아닌 attempt에 markCancelFailed 호출 시 예외가 발생한다")
	@Test
	void markCancelFailed_whenStatusNotRequested_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.markCancelSucceeded(LocalDateTime.now());

		// when & then
		assertThatThrownBy(
			() -> attempt.markCancelFailed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "failed", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("APPROVE type attempt에 markCancelFailed 호출 시 예외가 발생한다")
	@Test
	void markCancelFailed_whenTypeIsApprove_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

		// when & then
		assertThatThrownBy(
			() -> attempt.markCancelFailed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "failed", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_TYPE_MISMATCH));
	}
}
