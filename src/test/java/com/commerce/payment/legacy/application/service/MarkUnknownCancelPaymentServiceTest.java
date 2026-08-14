package com.commerce.payment.legacy.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class MarkUnknownCancelPaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private MarkUnknownCancelPaymentService markUnknownCancelPaymentService;

	@DisplayName("markUnknown transition: REQUESTED 상태면 취소 이력을 UNKNOWN으로 마킹한다")
	@Test
	void markUnknown_whenRequested_marksUnknown() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		Payment payment = Payment.createCancelRequested(1L, "PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		markUnknownCancelPaymentService.markUnknown("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			"취소 결과 불명", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getFailDetail()).isEqualTo("취소 결과 불명");
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
		then(paymentRepository).should().saveChecked(payment);
	}

	@DisplayName("markUnknown transition: REQUESTED가 아니면 도메인 가드가 PAYMENT_STATUS_TRANSITION_NOT_ALLOWED를 전파한다 (skip은 useCase 책임)")
	@Test
	void markUnknown_whenNotRequested_throwsTransitionNotAllowed() {
		// given: 이미 SUCCEEDED 등으로 확정된 취소 이력은 종착 전이가 막힌다
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		Payment payment = Payment.createCancelRequested(1L, "PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		payment.succeed(LocalDateTime.of(2026, 3, 3, 16, 20));
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> markUnknownCancelPaymentService.markUnknown("PAY-1", PaymentProvider.NAVERPAY,
			"payment-id-1", "취소 결과 불명", respondedAt))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(paymentRepository).should(never()).saveChecked(any(Payment.class));
	}
}
