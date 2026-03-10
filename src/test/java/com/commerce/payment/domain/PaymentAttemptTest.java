package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAttemptTest {

	@DisplayName("승인 성공 처리 시 상태와 응답 시간이 갱신된다")
	@Test
	void approveSucceed_whenCalled_updateStatusAndRespondedAt() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 10);

		// when
		attempt.approveSucceed(respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
		assertThat(attempt.getFailCode()).isNull();
		assertThat(attempt.getFailDetail()).isNull();
	}

	@DisplayName("취소 실패 처리 시 실패 사유와 응답 시간이 저장된다")
	@Test
	void failCancel_whenCalled_updateFailedState() {
		// given
		PaymentAttempt attempt = PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 5, 20, 20);

		// when
		attempt.failCancel(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "cancel failed", respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getFailCode()).isEqualTo(PaymentAttemptFailCode.PG_REQUEST_REJECTED);
		assertThat(attempt.getFailDetail()).isEqualTo("cancel failed");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}
}
