package com.commerce.payment.application.service;

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

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class MarkUnknownApprovePaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private MarkUnknownApprovePaymentService markUnknownApprovePaymentService;

	@DisplayName("markUnknown transition: REQUESTED 상태이면 UNKNOWN으로 전이한다")
	@Test
	void markUnknown_whenRequested_marksUnknown() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		markUnknownApprovePaymentService.markUnknown("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			"pg timeout", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getFailDetail()).isEqualTo("pg timeout");
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
		then(paymentRepository).should().saveChecked(payment);
	}

	@DisplayName("markUnknown transition: REQUESTED가 아니면 도메인 가드가 PAYMENT_STATUS_TRANSITION_NOT_ALLOWED를 전파한다 (skip은 useCase 책임)")
	@Test
	void markUnknown_whenNotRequested_throwsTransitionNotAllowed() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		payment.succeed(LocalDateTime.of(2026, 3, 3, 16, 20));
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> markUnknownApprovePaymentService.markUnknown("PAY-1", PaymentProvider.NAVERPAY,
			"payment-id-1", "pg timeout", LocalDateTime.of(2026, 3, 3, 16, 21)))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		then(paymentRepository).should(never()).saveChecked(any());
	}
}
