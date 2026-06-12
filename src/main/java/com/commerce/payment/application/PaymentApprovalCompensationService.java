package com.commerce.payment.application;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.exception.PaymentErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalCompensationService {

	// transition(별도 빈)이 던지는 도메인 예외 중 best-effort 보상에서 skip할 코드 집합 (ADR-L2).
	// 충돌(다른 주체가 먼저 종착) / 가드 위반(이미 종착) / 이력 없음은 단조 종착이므로 흡수한다. 흡수는 트랜잭션 경계 밖(useCase)에서만 한다.
	private static final Set<PaymentErrorCode> SKIPPABLE = EnumSet.of(
		PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED,
		PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED,
		PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND);

	private final PaymentApprovalRecordService paymentApprovalRecordService;
	private final PaymentCancellationService paymentCancellationService;
	private final NotificationPort notificationPort;

	/**
	 * CANCELED 주문의 UNKNOWN 결제가 대사에서 SUCCEEDED 확정을 시도한 경우 보상 취소(환불)를 수행한다 (ADR-L4, C).
	 * PG 보상 취소 → 통지(best-effort). 이중 환불은 runPgCancel 내부의 getOrCreate + REQUESTED 가드가 차단한다.
	 */
	public void compensateCanceledOrderApproval(Payment approvePayment, PgCanceller pgCanceller) {
		runPgCancel(
			approvePayment,
			PaymentFailCode.ORDER_CANCELED,
			"취소된 주문으로 인한 보상 환불",
			approvePayment.getAmount(),
			"취소된 주문의 결제 환불",
			pgCanceller
		);
		try {
			notificationPort.notifyManualReviewRequired(
				approvePayment.getOrderId(), approvePayment.getMerchantPayKey(), "CANCELED 주문 결제 보상 취소"
			);
		} catch (Exception ex) {
			log.warn("보상 취소 후 통지 실패 orderId={} merchantPayKey={}",
				approvePayment.getOrderId(), approvePayment.getMerchantPayKey(), ex);
		}
	}

	public void compensateMerchantKeyMismatch(Payment approvePayment) {
		// PG 결제 자체가 없으므로 cancel 없이 approve fail만 (skip 가능).
		failSkippable(
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

	private void runPgCancel(
		Payment approvePayment,
		PaymentFailCode failCode,
		String failDetail,
		int cancelAmount,
		String cancelReason,
		PgCanceller pgCanceller
	) {
		LocalDateTime now = LocalDateTime.now();
		// approve payment가 race window에서 이미 SUCCEEDED 상태가 됐어도 PG cancel은 멈추지 않는다. 종착/충돌이면 approve fail만 skip한다.
		failSkippable(
			approvePayment.getMerchantPayKey(), approvePayment.getProvider(), approvePayment.getPgPaymentId(),
			failCode, failDetail, now
		);

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
				case UNKNOWN -> markUnknownSkippable(
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

	/**
	 * approve fail transition을 호출하되 best-effort 보상에서 흡수 대상(ADR-L2 SKIPPABLE) 도메인 예외는 skip한다.
	 * transition은 별도 빈(public @Transactional)이라 충돌 시 그 트랜잭션만 깨끗이 rollback되고, 흡수 catch는 트랜잭션 경계 밖에서 일어난다.
	 */
	private void failSkippable(
		String merchantPayKey, PaymentProvider provider, String pgPaymentId,
		PaymentFailCode failCode, String failDetail, LocalDateTime respondedAt
	) {
		try {
			paymentApprovalRecordService.fail(merchantPayKey, provider, pgPaymentId, failCode, failDetail, respondedAt);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("보상 실패 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), merchantPayKey, pgPaymentId);
				return;
			}
			throw ex;
		}
	}

	/**
	 * cancel markUnknown transition을 호출하되 흡수 대상(ADR-L2 SKIPPABLE) 도메인 예외는 skip한다. (failSkippable과 동일 구조)
	 */
	private void markUnknownSkippable(
		String merchantPayKey, PaymentProvider provider, String pgPaymentId,
		String failDetail, LocalDateTime respondedAt
	) {
		try {
			paymentCancellationService.markUnknown(merchantPayKey, provider, pgPaymentId, failDetail, respondedAt);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("취소 UNKNOWN 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), merchantPayKey, pgPaymentId);
				return;
			}
			throw ex;
		}
	}
}
