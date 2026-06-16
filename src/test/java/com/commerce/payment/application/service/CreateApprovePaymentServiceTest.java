package com.commerce.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.commerce.payment.domain.repository.PaymentReservationRepository;

@ExtendWith(MockitoExtension.class)
class CreateApprovePaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private PaymentReservationRepository paymentReservationRepository;

	@InjectMocks
	private CreateApprovePaymentService createApprovePaymentService;

	@DisplayName("같은 결제 시도 이력이 없으면 reservation을 USED로 전이하고 승인 요청 이력을 생성하고 반환한다")
	@Test
	void create_whenPaymentNotExists_useAndCreatePayment() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());
		given(paymentReservationRepository.saveUsed(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0, PaymentReservation.class));
		given(paymentRepository.save(any(Payment.class)))
			.willAnswer(invocation -> invocation.getArgument(0, Payment.class));

		// when
		Payment result = createApprovePaymentService.create(reservation, "payment-id-1");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
		assertThat(result.getType()).isEqualTo(PaymentType.APPROVE);
		assertThat(result.getAmount()).isEqualTo(1000);
		assertThat(reservation.getStatus()).isEqualTo(com.commerce.payment.domain.PaymentReservationStatus.USED);
		assertThat(reservation.getReservedKey()).isNull();
		then(paymentReservationRepository).should().saveUsed(reservation);
	}

	@DisplayName("승인 시도 이력이 이미 존재하면 기존 이력을 반환한다")
	@Test
	void create_whenPaymentExists_returnExisting() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment existingPayment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existingPayment));

		// when
		Payment result = createApprovePaymentService.create(reservation, "payment-id-1");

		// then
		assertThat(result).isSameAs(existingPayment);
		then(paymentRepository).should(never()).save(any(Payment.class));
	}
}
