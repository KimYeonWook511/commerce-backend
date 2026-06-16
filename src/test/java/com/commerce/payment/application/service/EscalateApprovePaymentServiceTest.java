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
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class EscalateApprovePaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private EscalateApprovePaymentService escalateApprovePaymentService;

	@DisplayName("escalate 호출 시 escalation 가능 상태이면 escalatedAt을 기록하고 true를 반환한다")
	@Test
	void escalate_whenEscalatable_returnTrue() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-1");
		payment.markUnknown("pg timeout", now.minusMinutes(1));

		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		boolean result = escalateApprovePaymentService.escalate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id-1", now);

		// then
		assertThat(result).isTrue();
		then(paymentRepository).should().saveChecked(payment);
	}

	@DisplayName("escalate 호출 시 이미 escalation됐으면 false를 반환하고 저장하지 않는다")
	@Test
	void escalate_whenAlreadyEscalated_returnFalse() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-1");
		payment.markUnknown("pg timeout", now.minusMinutes(2));
		payment.escalate(now.minusMinutes(1));

		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		boolean result = escalateApprovePaymentService.escalate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id-1", now);

		// then
		assertThat(result).isFalse();
		then(paymentRepository).should(never()).saveChecked(any());
	}

	@DisplayName("escalate 호출 시 종착 상태(SUCCEEDED)이면 false를 반환하고 저장하지 않는다")
	@Test
	void escalate_whenTerminalStatus_returnFalse() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-1");
		payment.succeed(now.minusMinutes(1));

		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		boolean result = escalateApprovePaymentService.escalate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id-1", now);

		// then
		assertThat(result).isFalse();
		then(paymentRepository).should(never()).saveChecked(any());
	}

	@DisplayName("escalate 호출 시 이력이 없으면 PAYMENT_RECORD_NOT_FOUND를 전파한다")
	@Test
	void escalate_whenPaymentNotFound_throwsRecordNotFound() {
		// given
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1")))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> escalateApprovePaymentService.escalate(
			"PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id-1", LocalDateTime.of(2026, 3, 3, 16, 21)))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND);
		then(paymentRepository).should(never()).saveChecked(any());
	}
}
