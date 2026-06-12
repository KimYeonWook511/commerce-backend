package com.commerce.payment.application;

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
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private PaymentCancellationService paymentCancellationService;

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
		Payment result = paymentCancellationService.getOrCreate(
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
		Payment result = paymentCancellationService.getOrCreate(
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
		assertThatThrownBy(() -> paymentCancellationService.getOrCreate(
			1L, "PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 2000))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_RECORD_AMOUNT_MISMATCH);
		then(paymentRepository).should(never()).save(any(Payment.class));
	}

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
		paymentCancellationService.succeed("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", respondedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
	}

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
		paymentCancellationService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
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
		paymentCancellationService.markUnknown("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
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
		assertThatThrownBy(() -> paymentCancellationService.markUnknown("PAY-1", PaymentProvider.NAVERPAY,
			"payment-id-1", "취소 결과 불명", respondedAt))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(paymentRepository).should(never()).saveChecked(any(Payment.class));
	}
}
