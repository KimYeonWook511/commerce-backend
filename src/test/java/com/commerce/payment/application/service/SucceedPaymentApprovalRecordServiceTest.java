package com.commerce.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
class SucceedPaymentApprovalRecordServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private SucceedPaymentApprovalRecordService succeedPaymentApprovalRecordService;

	@DisplayName("succeedApprovalRecordOnly 호출 시 결제 기록이 SUCCEEDED로 전이된다")
	@Test
	void succeedApprovalRecordOnly_whenPaymentExists_updatePayment() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-1");

		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		Payment result = succeedPaymentApprovalRecordService.succeedApprovalRecordOnly(payment, now);

		// then
		assertThat(result).isSameAs(payment);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(paymentRepository).should().saveApproved(payment);
	}

	@DisplayName("succeedApprovalRecordOnly 호출 시 이미 SUCCEEDED이면 멱등으로 현재 상태를 반환한다")
	@Test
	void succeedApprovalRecordOnly_whenAlreadySucceeded_returnIdempotent() {
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
		Payment result = succeedPaymentApprovalRecordService.succeedApprovalRecordOnly(payment, now);

		// then
		assertThat(result).isSameAs(payment);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(paymentRepository).should().findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1"));
	}

	@DisplayName("succeedApprovalRecordOnly 호출 시 이력이 없으면 PAYMENT_RECORD_NOT_FOUND를 전파한다")
	@Test
	void succeedApprovalRecordOnly_whenPaymentNotFound_throwsRecordNotFound() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-1");

		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id-1")))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> succeedPaymentApprovalRecordService.succeedApprovalRecordOnly(payment, now))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND);
	}
}
