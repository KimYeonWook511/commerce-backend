package com.commerce.payment.application;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalCompensationService {

	private final PaymentApprovalAttemptService paymentApprovalAttemptService;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentCancellationAttemptService paymentCancellationAttemptService;

	public void compensateMerchantKeyMismatch(Payment approveAttempt) {
		// PG 결제 자체가 없으므로 cancel 없이 failIfRequested만.
		paymentApprovalAttemptService.failIfRequested(
			approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(), approveAttempt.getPgPaymentId(),
			PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치", LocalDateTime.now()
		);
	}

	public void compensateAmountMismatch(Payment approveAttempt, int responseTotalAmount, PgCanceller pgCanceller) {
		runPgCancel(approveAttempt,
			PaymentFailCode.AMOUNT_MISMATCH,
			String.format("attemptAmount=%d, responseTotalAmount=%d", approveAttempt.getAmount(), responseTotalAmount),
			responseTotalAmount,
			"승인 금액 불일치",
			pgCanceller
		);
	}

	public void compensateDuplicatePayment(Payment approveAttempt, Exception ex, PgCanceller pgCanceller) {
		runPgCancel(approveAttempt,
			PaymentFailCode.DUPLICATE_PAYMENT,
			Objects.toString(ex.getMessage(), "이미 완료된 결제 반영 시도"),
			approveAttempt.getAmount(),
			"이미 다른 결제가 완료된 주문으로 인한 취소",
			pgCanceller
		);
	}

	public void compensateUnexpected(Payment approveAttempt, Exception ex, PaymentFailCode failCode, PgCanceller pgCanceller) {
		runPgCancel(approveAttempt,
			failCode,
			Objects.toString(ex.getMessage(), "예상치 못한 오류 발생"),
			approveAttempt.getAmount(),
			"결제 완료 반영 실패로 인한 취소",
			pgCanceller
		);
	}

	private void runPgCancel(
		Payment approveAttempt,
		PaymentFailCode failCode,
		String failDetail,
		int cancelAmount,
		String cancelReason,
		PgCanceller pgCanceller
	) {
		LocalDateTime now = LocalDateTime.now();
		// approve attempt가 race window에서 이미 SUCCEEDED 상태가 됐어도 PG cancel은 멈추지 않는다. REQUESTED가 아니면 mark만 skip한다.
		paymentApprovalAttemptService.failIfRequested(
			approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(), approveAttempt.getPgPaymentId(),
			failCode, failDetail, now
		);

		if (paymentApprovalService.hasCompletedPayment(approveAttempt.getMerchantPayKey())) {
			log.warn(
				"Payment already completed, skipping PG cancel: merchantPayKey={}, pgPaymentId={}",
				approveAttempt.getMerchantPayKey(), approveAttempt.getPgPaymentId()
			);
			return;
		}

		Payment cancelAttempt = paymentCancellationAttemptService.getOrCreate(
			approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(),
			approveAttempt.getPgPaymentId(), cancelAmount
		);

		if (cancelAttempt.getStatus() != PaymentStatus.REQUESTED) {
			return;
		}

		try {
			CancelOutcome outcome = pgCanceller.cancel(cancelAttempt, cancelReason);
			switch (outcome.status()) {
				case SUCCESS -> paymentCancellationAttemptService.succeed(
					cancelAttempt.getMerchantPayKey(), cancelAttempt.getProvider(),
					cancelAttempt.getPgPaymentId(), now
				);
				case PROCESSING -> {} // no-op
				case FAILED -> paymentCancellationAttemptService.fail(
					cancelAttempt.getMerchantPayKey(), cancelAttempt.getProvider(),
					cancelAttempt.getPgPaymentId(), outcome.failCode(), outcome.failDetail(), now
				);
			}
		} catch (PaymentException ex) {
			log.warn(
				"Approved payment cancel failed: merchantPayKey={}, pgPaymentId={}, cancelReason={}, errorCode={}",
				cancelAttempt.getMerchantPayKey(), cancelAttempt.getPgPaymentId(),
				cancelReason, ex.getErrorCode()
			);
		}
	}
}
