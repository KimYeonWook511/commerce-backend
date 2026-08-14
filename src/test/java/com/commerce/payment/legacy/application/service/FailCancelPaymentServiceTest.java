package com.commerce.payment.legacy.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class FailCancelPaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private FailCancelPaymentService failCancelPaymentService;

	@DisplayName("취소 실패 시 결제 시도 이력의 실패 사유를 저장한다")
	@Test
	void fail_whenPaymentExists_updatePayment() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		Payment payment = Payment.createCancelRequested(1L, "PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		failCancelPaymentService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.PG_NETWORK_ERROR,
			"network error",
			respondedAt
		);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo(PaymentFailCode.PG_NETWORK_ERROR);
		assertThat(payment.getFailDetail()).isEqualTo("network error");
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
	}
}
