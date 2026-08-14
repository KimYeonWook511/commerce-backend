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
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class FailApprovePaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private FailApprovePaymentService failApprovePaymentService;

	@DisplayName("승인 실패 시 결제 시도 이력의 실패 사유를 저장한다")
	@Test
	void fail_whenPaymentExists_updatePayment() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		failApprovePaymentService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
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

	@DisplayName("fail transition: UNKNOWN 상태이면 실패로 전이한다 (대사 경로 보상)")
	@Test
	void fail_whenUnknown_updatePayment() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		payment.markUnknown("pg timeout", LocalDateTime.of(2026, 3, 3, 16, 20));
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		failApprovePaymentService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.ORDER_CANCELED,
			"취소된 주문 보상",
			respondedAt
		);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo(PaymentFailCode.ORDER_CANCELED);
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
		then(paymentRepository).should().saveChecked(payment);
	}

	@DisplayName("fail transition: 종착(SUCCEEDED) 상태이면 도메인 가드가 PAYMENT_STATUS_TRANSITION_NOT_ALLOWED를 전파한다 (skip은 useCase 책임)")
	@Test
	void fail_whenTerminalStatus_throwsTransitionNotAllowed() {
		// given
		LocalDateTime succeededAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		payment.succeed(succeededAt);
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when & then: 종착 전이 시 가드 위반은 catch하지 않고 전파한다
		assertThatThrownBy(() -> failApprovePaymentService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.APPROVE_PROCESS_FAILED, "compensation", LocalDateTime.of(2026, 3, 3, 16, 22)))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(paymentRepository).should(never()).saveChecked(any());
	}

	@DisplayName("fail transition: 이력이 없으면 PAYMENT_RECORD_NOT_FOUND를 전파한다 (skip은 useCase 책임)")
	@Test
	void fail_whenPaymentNotFound_throwsRecordNotFound() {
		// given
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> failApprovePaymentService.fail(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.APPROVE_PROCESS_FAILED, "compensation", LocalDateTime.of(2026, 3, 3, 16, 21)))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND);
		then(paymentRepository).should(never()).saveChecked(any());
	}
}
