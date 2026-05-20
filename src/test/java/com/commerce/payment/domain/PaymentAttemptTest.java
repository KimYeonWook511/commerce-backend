package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

class PaymentAttemptTest {

	@DisplayName("승인 성공 처리 시 상태와 응답 시간이 갱신된다")
	@Test
	void succeed_whenCalled_updateStatusAndRespondedAt() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		attempt.succeed(respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(attempt.getFailCode()).isNull();
		assertThat(attempt.getFailDetail()).isNull();
	}

	@DisplayName("취소 실패 처리 시 실패 사유와 응답 시간이 저장된다")
	@Test
	void fail_whenCalled_updateFailedState() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 20);

		// when
		attempt.fail(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "cancel failed", respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getFailCode()).isEqualTo(PaymentAttemptFailCode.PG_REQUEST_REJECTED);
		assertThat(attempt.getFailDetail()).isEqualTo("cancel failed");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("이미 SUCCEEDED 상태인 attempt에 succeed 호출 시 예외가 발생한다")
	@Test
	void succeed_whenStatusSucceeded_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.succeed(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("이미 FAILED 상태인 attempt에 succeed 호출 시 예외가 발생한다")
	@Test
	void succeed_whenStatusFailed_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.fail(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now());

		// when & then
		assertThatThrownBy(() -> attempt.succeed(LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("REQUESTED 상태가 아닌 attempt에 fail 호출 시 예외가 발생한다")
	@Test
	void fail_whenStatusNotRequested_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.succeed(LocalDateTime.now());

		// when & then
		assertThatThrownBy(
			() -> attempt.fail(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("FAILED 상태 attempt에 fail 호출 시 예외가 발생한다 (자기 전이 거부)")
	@Test
	void fail_whenStatusFailed_throwException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.fail(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now());

		// when & then
		assertThatThrownBy(
			() -> attempt.fail(PaymentAttemptFailCode.TIME_EXPIRED, "timeout", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
	}

	@DisplayName("merchantPayKey가 다르면 verifyApprovedResponse에서 PAYMENT_MERCHANT_KEY_MISMATCH를 던진다")
	@Test
	void verifyApprovedResponse_whenMerchantPayKeyMismatch_throwPaymentMerchantKeyMismatch() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

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
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

		// when & then
		assertThatThrownBy(() -> attempt.verifyApprovedResponse("PAY-1", 2000))
			.isInstanceOf(PaymentException.class)
			.satisfies(e -> assertThat(((PaymentException)e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
	}

	@DisplayName("merchantPayKey와 금액이 모두 일치하면 verifyApprovedResponse에서 예외가 발생하지 않는다")
	@Test
	void verifyApprovedResponse_whenBothMatch_noException() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);

		// when & then
		assertThatCode(() -> attempt.verifyApprovedResponse("PAY-1", 1000)).doesNotThrowAnyException();
	}
}
