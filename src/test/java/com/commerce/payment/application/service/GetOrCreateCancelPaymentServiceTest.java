package com.commerce.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class GetOrCreateCancelPaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private GetOrCreateCancelPaymentService getOrCreateCancelPaymentService;

	@DisplayName("취소 요청 이력이 없으면 취소 요청 이력을 생성한다")
	@Test
	void getOrCreate_whenCancelPaymentNotExists_createCancelPayment() {
		// given
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());
		given(paymentRepository.save(any(Payment.class)))
			.willAnswer(invocation -> invocation.getArgument(0, Payment.class));

		// when
		Payment result = getOrCreateCancelPaymentService.getOrCreate(
			1L, "PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
		assertThat(result.getType()).isEqualTo(PaymentType.CANCEL);
		assertThat(result.getAmount()).isEqualTo(1000);
	}

	@DisplayName("취소 요청 이력이 이미 존재하고 amount가 같으면 기존 이력을 반환한다")
	@Test
	void getOrCreate_whenCancelPaymentExistsWithSameAmount_returnExistingPayment() {
		// given
		Payment existingPayment = Payment.createCancelRequested(1L, "PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existingPayment));

		// when
		Payment result = getOrCreateCancelPaymentService.getOrCreate(
			1L, "PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result).isSameAs(existingPayment);
		then(paymentRepository).should(never()).save(any(Payment.class));
	}

	@DisplayName("취소 요청 이력이 이미 존재하고 amount가 다르면 예외를 던진다")
	@Test
	void getOrCreate_whenCancelPaymentExistsWithDifferentAmount_throwAmountMismatch() {
		// given
		Payment existing = Payment.createCancelRequested(
			1L, "PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		given(paymentRepository.findCancelPayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existing));

		// when & then
		assertThatThrownBy(() -> getOrCreateCancelPaymentService.getOrCreate(
			1L, "PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 2000))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_RECORD_AMOUNT_MISMATCH);
		then(paymentRepository).should(never()).save(any(Payment.class));
	}
}
