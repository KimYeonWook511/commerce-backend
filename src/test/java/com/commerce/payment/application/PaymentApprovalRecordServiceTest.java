package com.commerce.payment.application;

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
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalRecordServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private PaymentReservationRepository paymentReservationRepository;

	@InjectMocks
	private PaymentApprovalRecordService paymentApprovalRecordService;

	@DisplayName("같은 결제 시도 이력이 없으면 reservation을 USED로 전이하고 승인 요청 이력을 생성하고 반환한다")
	@Test
	void create_whenPaymentNotExists_useAndCreatePayment() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0, PaymentReservation.class));
		given(paymentRepository.save(any(Payment.class)))
			.willAnswer(invocation -> invocation.getArgument(0, Payment.class));

		// when
		Payment result = paymentApprovalRecordService.create(reservation, "payment-id-1");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
		assertThat(result.getType()).isEqualTo(PaymentType.APPROVE);
		assertThat(result.getAmount()).isEqualTo(1000);
		assertThat(reservation.getStatus()).isEqualTo(com.commerce.payment.domain.PaymentReservationStatus.USED);
		assertThat(reservation.getReservedKey()).isNull();
		then(paymentReservationRepository).should().save(reservation);
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
		Payment result = paymentApprovalRecordService.create(reservation, "payment-id-1");

		// then
		assertThat(result).isSameAs(existingPayment);
		then(paymentRepository).should(never()).save(any(Payment.class));
	}

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
		paymentApprovalRecordService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
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

	@DisplayName("보상 흐름 실패 처리 시 REQUESTED 상태이면 실패로 전이한다")
	@Test
	void failIfRequested_whenRequested_updatePayment() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		paymentApprovalRecordService.failIfRequested("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.APPROVE_PROCESS_FAILED,
			"compensation",
			respondedAt
		);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo(PaymentFailCode.APPROVE_PROCESS_FAILED);
		assertThat(payment.getFailDetail()).isEqualTo("compensation");
		assertThat(payment.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("보상 흐름 실패 처리 시 REQUESTED가 아니면 상태를 갱신하지 않고 종료한다")
	@Test
	void failIfRequested_whenNotRequested_skipMark() {
		// given
		LocalDateTime succeededAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "payment-id-1");
		payment.succeed(succeededAt);
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(payment));

		// when
		paymentApprovalRecordService.failIfRequested("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.APPROVE_PROCESS_FAILED,
			"compensation",
			LocalDateTime.of(2026, 3, 3, 16, 22)
		);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getFailCode()).isNull();
		assertThat(payment.getRespondedAt()).isEqualTo(succeededAt);
	}

	@DisplayName("보상 흐름 실패 처리 시 이력이 없으면 상태를 갱신하지 않고 종료한다")
	@Test
	void failIfRequested_whenPaymentNotFound_skipMark() {
		// given
		given(paymentRepository.findApprovePayment(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());

		// when
		paymentApprovalRecordService.failIfRequested(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentFailCode.APPROVE_PROCESS_FAILED,
			"compensation",
			LocalDateTime.of(2026, 3, 3, 16, 21));

		// then
		then(paymentRepository).should(never()).save(any());
	}
}
