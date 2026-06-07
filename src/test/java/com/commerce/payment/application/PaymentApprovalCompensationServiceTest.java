package com.commerce.payment.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalCompensationServiceTest {

	@Mock
	private PaymentApprovalRecordService paymentApprovalRecordService;

	@Mock
	private PaymentApprovalService paymentApprovalService;

	@Mock
	private PaymentCancellationService paymentCancellationService;

	@Mock
	private PgCanceller pgCanceller;

	@InjectMocks
	private PaymentApprovalCompensationService compensationService;

	@DisplayName("merchantKeyMismatch 보상: failIfRequested 호출, pgCanceller.cancel 미호출")
	@Test
	void compensateMerchantKeyMismatch_callsFailIfRequestedOnly() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);

		compensationService.compensateMerchantKeyMismatch(approvePayment);

		then(paymentApprovalRecordService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH), any(), any()
		);
		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("amountMismatch 보상: hasCompletedPayment=false, outcome=SUCCESS → succeed 호출")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndSuccess_callsSucceed() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("amountMismatch 보상: hasCompletedPayment=false, outcome=PROCESSING → no-op")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndProcessing_noOp() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.processing());

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should(never()).succeed(any(), any(), any(), any());
		then(paymentCancellationService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("amountMismatch 보상: hasCompletedPayment=false, outcome=FAILED → fail 호출")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndFailed_callsFail() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
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

	@DisplayName("amountMismatch 보상: hasCompletedPayment=false, outcome=UNKNOWN → markUnknownIfRequested 호출")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndUnknown_callsMarkUnknown() {
		// PG 취소 결과 불명 시 cancel 기록을 FAILED로 박제하지 않고 UNKNOWN으로 보존해 대사 대상으로 남긴다 (#219)
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.unknown("취소 결과 불명: 네트워크 오류"));

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(paymentCancellationService).should().markUnknownIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any(), any()
		);
		then(paymentCancellationService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("amountMismatch 보상: hasCompletedPayment=true → pgCanceller.cancel 미호출")
	@Test
	void compensateAmountMismatch_whenCompensationNotRequired_skipPgCancel() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(true);

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
		then(paymentCancellationService).should(never()).getOrCreate(any(), any(), any(), any(), anyInt());
	}

	@DisplayName("amountMismatch 보상: cancelPayment 상태가 REQUESTED가 아니면 pgCanceller.cancel 미호출")
	@Test
	void compensateAmountMismatch_whenCancelPaymentNotRequested_skipPgCancel() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);
		cancelPayment.succeed(LocalDateTime.now());

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
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

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(any(), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR));

		compensationService.compensateAmountMismatch(approvePayment, 2000, pgCanceller);
	}

	@DisplayName("duplicatePayment 보상: hasCompletedPayment=false, outcome=SUCCESS → succeed 호출")
	@Test
	void compensateDuplicatePayment_whenCompensationRequiredAndSuccess_callsSucceed() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		compensationService.compensateDuplicatePayment(approvePayment, ex, pgCanceller);

		then(paymentCancellationService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("duplicatePayment 보상: hasCompletedPayment=true → pgCanceller.cancel 미호출")
	@Test
	void compensateDuplicatePayment_whenCompensationNotRequired_skipPgCancel() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(true);

		compensationService.compensateDuplicatePayment(approvePayment, ex, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("duplicatePayment 보상: hasCompletedPayment=false, outcome=FAILED → fail 호출")
	@Test
	void compensateDuplicatePayment_whenCompensationRequiredAndFailed_callsFail() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
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

	@DisplayName("unexpected 보상: hasCompletedPayment=false, outcome=SUCCESS → succeed 호출")
	@Test
	void compensateUnexpected_whenCompensationRequiredAndSuccess_callsSucceed() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new RuntimeException("unexpected");

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		compensationService.compensateUnexpected(approvePayment, ex, PaymentFailCode.APPROVE_PROCESS_FAILED, pgCanceller);

		then(paymentCancellationService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("unexpected 보상: hasCompletedPayment=true → pgCanceller.cancel 미호출")
	@Test
	void compensateUnexpected_whenCompensationNotRequired_skipPgCancel() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Exception ex = new RuntimeException("unexpected");

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(true);

		compensationService.compensateUnexpected(approvePayment, ex, PaymentFailCode.APPROVE_PROCESS_FAILED, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("unexpected 보상: hasCompletedPayment=false, outcome=FAILED → fail 호출")
	@Test
	void compensateUnexpected_whenCompensationRequiredAndFailed_callsFail() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new RuntimeException("unexpected");

		given(paymentApprovalService.hasCompletedPayment("PAY-1")).willReturn(false);
		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.failed(PaymentFailCode.PG_SERVER_ERROR, "서버 오류"));

		compensationService.compensateUnexpected(approvePayment, ex, PaymentFailCode.APPROVE_PROCESS_FAILED, pgCanceller);

		then(paymentCancellationService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.PG_SERVER_ERROR), eq("서버 오류"), any()
		);
	}

	@DisplayName("compensateDuplicateApproval: pgCanceller.cancel 성공 시 succeed 호출 + approve payment failIfRequested")
	@Test
	void compensateDuplicateApproval_whenPgCancelSucceeds_callsSucceedAndFailIfRequested() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		compensationService.compensateDuplicateApproval(approvePayment, pgCanceller);

		then(paymentCancellationService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
		then(paymentApprovalRecordService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.DUPLICATE_PAYMENT), any(), any()
		);
	}

	@DisplayName("compensateDuplicateApproval: pgCanceller.cancel PROCESSING → succeed/fail 미호출 + approve payment failIfRequested")
	@Test
	void compensateDuplicateApproval_whenPgCancelProcessing_noSucceedOrFail() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.processing());

		compensationService.compensateDuplicateApproval(approvePayment, pgCanceller);

		then(paymentCancellationService).should(never()).succeed(any(), any(), any(), any());
		then(paymentCancellationService).should(never()).fail(any(), any(), any(), any(), any(), any());
		then(paymentApprovalRecordService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.DUPLICATE_PAYMENT), any(), any()
		);
	}

	@DisplayName("compensateDuplicateApproval: pgCanceller.cancel 실패 시 fail 호출 + approve payment failIfRequested")
	@Test
	void compensateDuplicateApproval_whenPgCancelFails_callsFailAndFailIfRequested() {
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.failed(PaymentFailCode.CANCEL_PROCESS_FAILED, "취소 실패"));

		compensationService.compensateDuplicateApproval(approvePayment, pgCanceller);

		then(paymentCancellationService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.CANCEL_PROCESS_FAILED), eq("취소 실패"), any()
		);
		then(paymentApprovalRecordService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.DUPLICATE_PAYMENT), any(), any()
		);
	}

	@DisplayName("compensateDuplicateApproval: pgCanceller.cancel 결과 불명(UNKNOWN) 시 markUnknownIfRequested 호출 + approve payment failIfRequested")
	@Test
	void compensateDuplicateApproval_whenPgCancelUnknown_callsMarkUnknownAndFailIfRequested() {
		// PG 취소 결과 불명 시 cancel 기록을 FAILED로 박제하지 않고 UNKNOWN으로 보존해 대사 대상으로 남긴다 (#219)
		Payment approvePayment = createApprovePayment("PAY-1", "pg-id", 1000);
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);

		given(paymentCancellationService.getOrCreate(eq(1L), eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelPayment);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.unknown("취소 결과 불명: 네트워크 오류"));

		compensationService.compensateDuplicateApproval(approvePayment, pgCanceller);

		then(paymentCancellationService).should().markUnknownIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any(), any()
		);
		then(paymentCancellationService).should(never()).fail(any(), any(), any(), any(), any(), any());
		then(paymentApprovalRecordService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.DUPLICATE_PAYMENT), any(), any()
		);
	}

	private Payment createApprovePayment(String merchantPayKey, String pgPaymentId, int amount) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, amount, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		return Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
	}
}
