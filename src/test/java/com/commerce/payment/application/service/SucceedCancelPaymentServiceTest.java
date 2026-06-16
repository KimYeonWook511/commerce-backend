package com.commerce.payment.application.service;

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

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class SucceedCancelPaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private SucceedCancelPaymentService succeedCancelPaymentService;

	@DisplayName("취소 성공 시 결제 시도 이력의 상태를 SUCCEEDED로 갱신한다")
	@Test
	void succeed_whenPaymentExists_updatePayment() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		Payment payment = Payment.createCancelRequested(1L, "PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		succeedCancelPaymentService.succeed("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
	}
}
