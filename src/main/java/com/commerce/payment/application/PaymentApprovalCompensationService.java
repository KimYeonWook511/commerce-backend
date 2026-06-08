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

	private final PaymentApprovalRecordService paymentApprovalRecordService;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentCancellationService paymentCancellationService;

	public void compensateMerchantKeyMismatch(Payment approvePayment) {
		// PG 결제 자체가 없으므로 cancel 없이 failIfRequested만.
		paymentApprovalRecordService.failIfRequested(
			approvePayment.getMerchantPayKey(), approvePayment.getProvider(), approvePayment.getPgPaymentId(),
			PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치", LocalDateTime.now()
		);
	}

	public void compensateAmountMismatch(Payment approvePayment, int responseTotalAmount, PgCanceller pgCanceller) {
		runPgCancel(approvePayment,
			PaymentFailCode.AMOUNT_MISMATCH,
			String.format("approveAmount=%d, responseTotalAmount=%d", approvePayment.getAmount(), responseTotalAmount),
			responseTotalAmount,
			"승인 금액 불일치",
			pgCanceller
		);
	}

	public void compensateDuplicatePayment(Payment approvePayment, Exception ex, PgCanceller pgCanceller) {
		runPgCancel(approvePayment,
			PaymentFailCode.DUPLICATE_PAYMENT,
			Objects.toString(ex.getMessage(), "이미 완료된 결제 반영 시도"),
			approvePayment.getAmount(),
			"이미 다른 결제가 완료된 주문으로 인한 취소",
			pgCanceller
		);
	}

	public void compensateUnexpected(Payment approvePayment, Exception ex, PaymentFailCode failCode, PgCanceller pgCanceller) {
		runPgCancel(approvePayment,
			failCode,
			Objects.toString(ex.getMessage(), "예상치 못한 오류 발생"),
			approvePayment.getAmount(),
			"결제 완료 반영 실패로 인한 취소",
			pgCanceller
		);
	}

	private void runPgCancel(
		Payment approvePayment,
		PaymentFailCode failCode,
		String failDetail,
		int cancelAmount,
		String cancelReason,
		PgCanceller pgCanceller
	) {
		LocalDateTime now = LocalDateTime.now();
		// approve payment가 race window에서 이미 SUCCEEDED 상태가 됐어도 PG cancel은 멈추지 않는다. REQUESTED가 아니면 mark만 skip한다.
		paymentApprovalRecordService.failIfRequested(
			approvePayment.getMerchantPayKey(), approvePayment.getProvider(), approvePayment.getPgPaymentId(),
			failCode, failDetail, now
		);

		if (paymentApprovalService.hasCompletedPayment(approvePayment.getMerchantPayKey())) {
			log.warn(
				"Payment already completed, skipping PG cancel: merchantPayKey={}, pgPaymentId={}",
				approvePayment.getMerchantPayKey(), approvePayment.getPgPaymentId()
			);
			return;
		}

		Payment cancelPayment = paymentCancellationService.getOrCreate(
			approvePayment.getOrderId(),
			approvePayment.getMerchantPayKey(), approvePayment.getProvider(),
			approvePayment.getPgPaymentId(), cancelAmount
		);

		if (cancelPayment.getStatus() != PaymentStatus.REQUESTED) {
			return;
		}

		try {
			CancelOutcome outcome = pgCanceller.cancel(cancelPayment, cancelReason);
			switch (outcome.status()) {
				case SUCCESS -> paymentCancellationService.succeed(
					cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
					cancelPayment.getPgPaymentId(), now
				);
				case PROCESSING -> {} // no-op
				case FAILED -> paymentCancellationService.fail(
					cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
					cancelPayment.getPgPaymentId(), outcome.failCode(), outcome.failDetail(), now
				);
				// PG 취소 결과 불명: cancel 기록을 UNKNOWN 보존해 대사 대상으로 남긴다 (#219)
				case UNKNOWN -> paymentCancellationService.markUnknownIfRequested(
					cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
					cancelPayment.getPgPaymentId(), outcome.failDetail(), now
				);
			}
		} catch (PaymentException ex) {
			log.warn(
				"Approved payment cancel failed: merchantPayKey={}, pgPaymentId={}, cancelReason={}, errorCode={}",
				cancelPayment.getMerchantPayKey(), cancelPayment.getPgPaymentId(),
				cancelReason, ex.getErrorCode()
			);
		}
	}
}
