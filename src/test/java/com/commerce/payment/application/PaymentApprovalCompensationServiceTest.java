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
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalCompensationServiceTest {

	@Mock
	private PaymentApprovalAttemptService paymentApprovalAttemptService;

	@Mock
	private PaymentApprovalService paymentApprovalService;

	@Mock
	private PaymentCancellationAttemptService paymentCancellationAttemptService;

	@Mock
	private PgCanceller pgCanceller;

	@InjectMocks
	private PaymentApprovalCompensationService compensationService;

	@DisplayName("merchantKeyMismatch 보상: failIfRequested 호출, pgCanceller.cancel 미호출")
	@Test
	void compensateMerchantKeyMismatch_callsFailIfRequestedOnly() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);

		compensationService.compensateMerchantKeyMismatch(attempt);

		then(paymentApprovalAttemptService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH), any(), any()
		);
		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("amountMismatch 보상: isCompensationRequired=true, outcome=SUCCESS → succeed 호출")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndSuccess_callsSucceed() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any())).willReturn(CancelOutcome.success());

		compensationService.compensateAmountMismatch(attempt, 2000, pgCanceller);

		then(paymentCancellationAttemptService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("amountMismatch 보상: isCompensationRequired=true, outcome=PROCESSING → no-op")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndProcessing_noOp() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any())).willReturn(CancelOutcome.processing());

		compensationService.compensateAmountMismatch(attempt, 2000, pgCanceller);

		then(paymentCancellationAttemptService).should(never()).succeed(any(), any(), any(), any());
		then(paymentCancellationAttemptService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("amountMismatch 보상: isCompensationRequired=true, outcome=FAILED → fail 호출")
	@Test
	void compensateAmountMismatch_whenCompensationRequiredAndFailed_callsFail() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any()))
			.willReturn(CancelOutcome.failed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "reject"));

		compensationService.compensateAmountMismatch(attempt, 2000, pgCanceller);

		then(paymentCancellationAttemptService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentAttemptFailCode.PG_REQUEST_REJECTED), eq("reject"), any()
		);
	}

	@DisplayName("amountMismatch 보상: isCompensationRequired=false → pgCanceller.cancel 미호출")
	@Test
	void compensateAmountMismatch_whenCompensationNotRequired_skipPgCancel() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(false);

		compensationService.compensateAmountMismatch(attempt, 2000, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
		then(paymentCancellationAttemptService).should(never()).getOrCreate(any(), any(), any(), anyInt());
	}

	@DisplayName("amountMismatch 보상: cancelAttempt 상태가 REQUESTED가 아니면 pgCanceller.cancel 미호출")
	@Test
	void compensateAmountMismatch_whenCancelAttemptNotRequested_skipPgCancel() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);
		cancelAttempt.succeed(LocalDateTime.now());

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelAttempt);

		compensationService.compensateAmountMismatch(attempt, 2000, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("amountMismatch 보상: pgCanceller.cancel에서 PaymentException 발생 시 swallow")
	@Test
	void compensateAmountMismatch_whenPgCancelThrowsPaymentException_swallow() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 2000, PaymentProvider.NAVERPAY);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(2000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(any(), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR));

		compensationService.compensateAmountMismatch(attempt, 2000, pgCanceller);
	}

	@DisplayName("duplicatePayment 보상: isCompensationRequired=true, outcome=SUCCESS → succeed 호출")
	@Test
	void compensateDuplicatePayment_whenCompensationRequiredAndSuccess_callsSucceed() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any())).willReturn(CancelOutcome.success());

		compensationService.compensateDuplicatePayment(attempt, ex, pgCanceller);

		then(paymentCancellationAttemptService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("duplicatePayment 보상: isCompensationRequired=false → pgCanceller.cancel 미호출")
	@Test
	void compensateDuplicatePayment_whenCompensationNotRequired_skipPgCancel() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(false);

		compensationService.compensateDuplicatePayment(attempt, ex, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("duplicatePayment 보상: isCompensationRequired=true, outcome=FAILED → fail 호출")
	@Test
	void compensateDuplicatePayment_whenCompensationRequiredAndFailed_callsFail() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any()))
			.willReturn(CancelOutcome.failed(PaymentAttemptFailCode.CANCEL_PROCESS_FAILED, "취소 실패"));

		compensationService.compensateDuplicatePayment(attempt, ex, pgCanceller);

		then(paymentCancellationAttemptService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentAttemptFailCode.CANCEL_PROCESS_FAILED), eq("취소 실패"), any()
		);
	}

	@DisplayName("unexpected 보상: isCompensationRequired=true, outcome=SUCCESS → succeed 호출")
	@Test
	void compensateUnexpected_whenCompensationRequiredAndSuccess_callsSucceed() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new RuntimeException("unexpected");

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any())).willReturn(CancelOutcome.success());

		compensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, pgCanceller);

		then(paymentCancellationAttemptService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
	}

	@DisplayName("unexpected 보상: isCompensationRequired=false → pgCanceller.cancel 미호출")
	@Test
	void compensateUnexpected_whenCompensationNotRequired_skipPgCancel() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new RuntimeException("unexpected");

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(false);

		compensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, pgCanceller);

		then(pgCanceller).should(never()).cancel(any(), any());
	}

	@DisplayName("unexpected 보상: isCompensationRequired=true, outcome=FAILED → fail 호출")
	@Test
	void compensateUnexpected_whenCompensationRequiredAndFailed_callsFail() {
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		Exception ex = new RuntimeException("unexpected");

		given(paymentApprovalService.isCompensationRequired("PAY-1")).willReturn(true);
		given(paymentCancellationAttemptService.getOrCreate(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq(1000)))
			.willReturn(cancelAttempt);
		given(pgCanceller.cancel(eq(cancelAttempt), any()))
			.willReturn(CancelOutcome.failed(PaymentAttemptFailCode.PG_SERVER_ERROR, "서버 오류"));

		compensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, pgCanceller);

		then(paymentCancellationAttemptService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentAttemptFailCode.PG_SERVER_ERROR), eq("서버 오류"), any()
		);
	}
}
