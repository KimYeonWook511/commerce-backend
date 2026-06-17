package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.application.service.FailCancelPaymentService;
import com.commerce.payment.application.service.MarkUnknownCancelPaymentService;
import com.commerce.payment.application.service.SucceedCancelPaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class RefundExecutionUseCaseTest {

	@Mock
	private SucceedCancelPaymentService succeedCancelPaymentService;

	@Mock
	private FailCancelPaymentService failCancelPaymentService;

	@Mock
	private MarkUnknownCancelPaymentService markUnknownCancelPaymentService;

	@Mock
	private PgCanceller pgCanceller;

	@InjectMocks
	private RefundExecutionUseCase refundExecutionUseCase;

	@DisplayName("CANCEL REQUESTED 상태에서 PG 취소 성공 시 succeed 호출 후 SUCCESS 반환")
	@Test
	void execute_whenPgSucceeds_callsSucceedAndReturnsSuccess() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());

		// when
		CancelOutcome result = refundExecutionUseCase.execute(cancelPayment, pgCanceller);

		// then
		assertThat(result.status()).isEqualTo(CancelOutcome.Status.SUCCESS);
		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any()
		);
		then(failCancelPaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
		then(markUnknownCancelPaymentService).should(never()).markUnknown(any(), any(), any(), any(), any());
	}

	@DisplayName("CANCEL REQUESTED 상태에서 PG 취소 처리중이면 no-op 후 PROCESSING 반환")
	@Test
	void execute_whenPgProcessing_noOpAndReturnsProcessing() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.processing());

		// when
		CancelOutcome result = refundExecutionUseCase.execute(cancelPayment, pgCanceller);

		// then
		assertThat(result.status()).isEqualTo(CancelOutcome.Status.PROCESSING);
		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
		then(failCancelPaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
		then(markUnknownCancelPaymentService).should(never()).markUnknown(any(), any(), any(), any(), any());
	}

	@DisplayName("CANCEL REQUESTED 상태에서 PG 취소 실패 시 fail 호출")
	@Test
	void execute_whenPgFails_callsFail() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.failed(PaymentFailCode.PG_REQUEST_REJECTED, "거절됨"));

		// when
		refundExecutionUseCase.execute(cancelPayment, pgCanceller);

		// then
		then(failCancelPaymentService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"),
			eq(PaymentFailCode.PG_REQUEST_REJECTED), eq("거절됨"), any()
		);
		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
	}

	@DisplayName("CANCEL REQUESTED 상태에서 PG 취소 결과 불명 시 markUnknown 호출")
	@Test
	void execute_whenPgUnknown_callsMarkUnknown() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(eq(cancelPayment), any()))
			.willReturn(CancelOutcome.unknown("네트워크 오류"));

		// when
		refundExecutionUseCase.execute(cancelPayment, pgCanceller);

		// then
		then(markUnknownCancelPaymentService).should().markUnknown(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), eq("네트워크 오류"), any()
		);
		then(failCancelPaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("CANCEL이 이미 SUCCEEDED면 PG 호출 없이 SUCCESS 반환 (멱등 — 응답 COMPLETED 수렴)")
	@Test
	void execute_whenCancelAlreadySucceeded_skipsPgCallAndReturnsSuccess() {
		// given: CANCEL을 SUCCEEDED 상태로 전이시킨다
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.succeed(LocalDateTime.now());

		// when
		CancelOutcome result = refundExecutionUseCase.execute(cancelPayment, pgCanceller);

		// then: 이미 환불 완료이므로 SUCCESS로 응답이 COMPLETED에 수렴한다
		assertThat(result.status()).isEqualTo(CancelOutcome.Status.SUCCESS);
		then(pgCanceller).should(never()).cancel(any(), any());
		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
	}

	@DisplayName("PG 호출 자체가 예외를 던지면 markUnknown 호출 후 정상 종료 (best-effort)")
	@Test
	void execute_whenPgCallThrowsException_callsMarkUnknown() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(any(), any())).willThrow(new RuntimeException("연결 실패"));

		// when
		refundExecutionUseCase.execute(cancelPayment, pgCanceller);

		// then
		then(markUnknownCancelPaymentService).should().markUnknown(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-id"), any(), any()
		);
	}

	@DisplayName("succeed transition이 SKIPPABLE 예외를 던지면 흡수하고 정상 종료")
	@Test
	void execute_whenSucceedTransitionSkippable_absorbed() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED))
			.given(succeedCancelPaymentService).succeed(any(), any(), any(), any());

		// when & then: skip, 예외 전파 없음
		org.assertj.core.api.Assertions.assertThatCode(
			() -> refundExecutionUseCase.execute(cancelPayment, pgCanceller)
		).doesNotThrowAnyException();
	}

	@DisplayName("succeed transition이 SKIPPABLE 아닌 예외를 던지면 전파")
	@Test
	void execute_whenSucceedTransitionNonSkippable_rethrown() {
		// given
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(pgCanceller.cancel(eq(cancelPayment), any())).willReturn(CancelOutcome.success());
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH))
			.given(succeedCancelPaymentService).succeed(any(), any(), any(), any());

		// when & then
		org.assertj.core.api.Assertions.assertThatThrownBy(
			() -> refundExecutionUseCase.execute(cancelPayment, pgCanceller)
		).isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
	}
}
