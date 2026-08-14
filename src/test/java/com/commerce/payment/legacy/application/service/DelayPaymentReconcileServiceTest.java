package com.commerce.payment.legacy.application.service;

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

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.postprocess.target.PaymentPostProcessTargetPolicy;

@ExtendWith(MockitoExtension.class)
class DelayPaymentReconcileServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private DelayPaymentReconcileService delayPaymentReconcileService;

	// --- type 분기: APPROVE는 findApprovePayment, CANCEL은 findCancelPayment ---

	@DisplayName("type=APPROVE이면 findApprovePayment로 로드해 delayReconcile을 적용한다")
	@Test
	void delay_typeApprove_usesApprovePaymentFinder() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 6, 19, 12, 0);
		Payment payment = approvePayment("PAY-1", "pg-1");
		given(paymentRepository.findApprovePayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1")))
			.willReturn(Optional.of(payment));
		given(paymentRepository.saveChecked(payment)).willReturn(payment);

		// when
		delayPaymentReconcileService.delay("PAY-1", PaymentProvider.NAVERPAY, "pg-1", PaymentType.APPROVE, now);

		// then: findApprovePayment 호출됨, delayReconcile 적용됨
		then(paymentRepository).should().findApprovePayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1"));
		assertThat(payment.getNextReconcileAt())
			.isEqualTo(now.plus(PaymentPostProcessTargetPolicy.RECONCILE_BACKOFF));
	}

	@DisplayName("type=CANCEL이면 findCancelPayment로 로드해 delayReconcile을 적용한다")
	@Test
	void delay_typeCancel_usesCancelPaymentFinder() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 6, 19, 12, 0);
		Payment payment = cancelPayment("PAY-C-1", "pg-c-1");
		given(paymentRepository.findCancelPayment(eq("PAY-C-1"), eq(PaymentProvider.NAVERPAY), eq("pg-c-1")))
			.willReturn(Optional.of(payment));
		given(paymentRepository.saveChecked(payment)).willReturn(payment);

		// when
		delayPaymentReconcileService.delay("PAY-C-1", PaymentProvider.NAVERPAY, "pg-c-1", PaymentType.CANCEL, now);

		// then: findCancelPayment 호출됨, delayReconcile 적용됨
		then(paymentRepository).should().findCancelPayment(eq("PAY-C-1"), eq(PaymentProvider.NAVERPAY), eq("pg-c-1"));
		assertThat(payment.getNextReconcileAt())
			.isEqualTo(now.plus(PaymentPostProcessTargetPolicy.RECONCILE_BACKOFF));
	}

	// --- 낙관 락 충돌: PAYMENT_CONCURRENTLY_MODIFIED는 호출부에서 흡수되어야 한다 ---

	@DisplayName("saveChecked에서 낙관 락 충돌이 발생하면 PaymentException(PAYMENT_CONCURRENTLY_MODIFIED)이 전파된다")
	@Test
	void delay_saveCheckedConflict_throwsPaymentConcurrentlyModified() {
		// given
		LocalDateTime now = LocalDateTime.now();
		Payment payment = approvePayment("PAY-2", "pg-2");
		given(paymentRepository.findApprovePayment(eq("PAY-2"), eq(PaymentProvider.NAVERPAY), eq("pg-2")))
			.willReturn(Optional.of(payment));
		given(paymentRepository.saveChecked(payment))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED));

		// when / then
		assertThatThrownBy(() ->
			delayPaymentReconcileService.delay("PAY-2", PaymentProvider.NAVERPAY, "pg-2", PaymentType.APPROVE, now))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED);
	}

	// --- helpers ---

	private Payment approvePayment(String merchantPayKey, String pgPaymentId) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		payment.markUnknown("timeout", LocalDateTime.now().minusMinutes(2));
		return payment;
	}

	private Payment cancelPayment(String merchantPayKey, String pgPaymentId) {
		return Payment.createCancelRequested(1L, merchantPayKey, pgPaymentId, 1000, PaymentProvider.NAVERPAY);
	}
}
