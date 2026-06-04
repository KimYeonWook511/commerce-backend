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
import com.commerce.payment.exception.PaymentErrorCode;

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

	/**
	 * uk_payment_approved_order_key 위반 시 보상 — 이중 결제 감지.
	 * PG 에서 돈이 빠진 attempt 를 cancel + FAILED 마킹한다 (ADR-14, ADR-015).
	 * hasCompletedPayment 검사 없음: uk 위반이 이미 "다른 행이 SUCCEEDED" 임을 보장.
	 */
	public void compensateDuplicateApproval(Payment approveAttempt, PgCanceller pgCanceller) {
		log.error("이중 결제 감지 — 이미 결제된 주문 orderId={} merchantPayKey={}",
			approveAttempt.getOrderId(), approveAttempt.getMerchantPayKey());

		LocalDateTime now = LocalDateTime.now();
		Payment cancelAttempt = paymentCancellationAttemptService.getOrCreate(
			approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(),
			approveAttempt.getPgPaymentId(), approveAttempt.getAmount()
		);

		if (cancelAttempt.getStatus() == PaymentStatus.REQUESTED) {
			try {
				CancelOutcome outcome = pgCanceller.cancel(cancelAttempt, "이중 결제로 인한 보상 취소");
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
				log.warn("이중 결제 보상 취소 실패 merchantPayKey={} pgPaymentId={} errorCode={}",
					cancelAttempt.getMerchantPayKey(), cancelAttempt.getPgPaymentId(), ex.getErrorCode());
			}
		}

		// 원 approve attempt 를 FAILED 마킹 (보상 cancel 이후)
		paymentApprovalAttemptService.failIfRequested(
			approveAttempt.getMerchantPayKey(), approveAttempt.getProvider(), approveAttempt.getPgPaymentId(),
			PaymentFailCode.DUPLICATE_PAYMENT, "이중 결제 감지로 인한 실패 처리", now
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
