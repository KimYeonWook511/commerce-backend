package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.application.service.PaymentApprovalRecordService;
import com.commerce.payment.application.service.PaymentCancellationService;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalCompensationUseCaseTest {

	@Mock
	private PaymentApprovalRecordService paymentApprovalRecordService;

	@Mock
	private PaymentCancellationService paymentCancellationService;

	@Mock
	private PgCanceller pgCanceller;

	@InjectMocks
	private PaymentApprovalCompensationUseCase compensationService;

	@DisplayName("merchantKeyMismatch 보상: fail 호출, pgCanceller.cancel 미호출")
	@Test
	void compensateMerchantKeyMismatch_callsFailIfRequestedOnly() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);

		compensationService.compensateMerchantKeyMismatch(approvePayment);

		then(paymentApprovalRecordService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH), any(), any()
		);
		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("skip 래퍼: transition이 PAYMENT_CONCURRENTLY_MODIFIED를 던지면 흡수하고 예외를 전파하지 않는다 (트랜잭션 경계 밖 skip)")
	@Test
	void failSkippable_whenTransitionConflict_absorbed() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED))
			.given(paymentApprovalRecordService).fail(any(), any(), any(), any(), any(), any());

		// transition이 충돌을 던져도 보상 useCase는 skip하고 정상 종료한다
		assertThatCode(() -> compensationService.compensateMerchantKeyMismatch(approvePayment))
			.doesNotThrowAnyException();
	}

	@DisplayName("skip 래퍼: transition이 SKIPPABLE 아닌 도메인 예외를 던지면 그대로 전파한다")
	@Test
	void failSkippable_whenNonSkippableError_rethrown() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH))
			.given(paymentApprovalRecordService).fail(any(), any(), any(), any(), any(), any());

		assertThatThrownBy(() -> compensationService.compensateMerchantKeyMismatch(approvePayment))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
	}

	@DisplayName("amountMismatch 보상: PG cancel 성공 시 succeed 호출")
	@Test
	void compensateAmountMismatch_whenSuccess_callsSucceed() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("amountMismatch 보상: PG cancel 처리중이면 no-op")
	@Test
	void compensateAmountMismatch_whenProcessing_noOp() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.processing());

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should(never()).succeed(any(), any(), any(), any());
		then(paymentCancellationService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("amountMismatch 보상: PG cancel 실패 시 fail 호출")
	@Test
	void compensateAmountMismatch_whenFailed_callsFail() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.failed(PaymentFailCode.PG_REQUEST_REJECTED, "reject"));

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.PG_REQUEST_REJECTED), eq("reject"), any()
		);
	}

	@DisplayName("amountMismatch 보상: PG cancel 결과 불명 시 markUnknown 호출")
	@Test
	void compensateAmountMismatch_whenUnknown_callsMarkUnknown() {
		// PG 취소 결과 불명 시 cancel 기록을 FAILED로 박제하지 않고 UNKNOWN으로 보존해 대사 대상으로 남긴다 (#219)
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.unknown("취소 결과 불명: 네트워크 오류"));

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should().markUnknown(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any(), any()
		);
		then(paymentCancellationService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("amountMismatch 보상: cancelPayment 상태가 REQUESTED가 아니면 pgCanceller.cancel 미호출")
	@Test
	void compensateAmountMismatch_whenCancelPaymentNotRequested_skipPgCancel() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);
		cancelPayment.succeed(LocalDateTime.now());

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("amountMismatch 보상: pgCanceller.cancel에서 PaymentException 발생 시 swallow")
	@Test
	void compensateAmountMismatch_whenPgCancelThrowsPaymentException_swallow() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(any(), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR));

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);
	}

	@DisplayName("duplicatePayment 보상: PG cancel 성공 시 succeed 호출")
	@Test
	void compensateDuplicatePayment_whenSuccess_callsSucceed() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		compensationService.compensateDuplicatePayment(approvePayment, ex, pgCanceller);

		then(paymentCancellationService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("duplicatePayment 보상: PG cancel 실패 시 fail 호출")
	@Test
	void compensateDuplicatePayment_whenFailed_callsFail() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.failed(PaymentFailCode.CANCEL_PROCESS_FAILED, "취소 실패"));

		compensationService.compensateDuplicatePayment(approvePayment, ex, pgCanceller);

		then(paymentCancellationService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.CANCEL_PROCESS_FAILED), eq("취소 실패"), any()
		);
	}

	private Payment createApprovePayment(String merchantPayKey, String pgPaymentId, int amount) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, amount, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		return Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
	}
}
