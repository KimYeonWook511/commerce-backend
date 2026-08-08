package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.commerce.common.exception.ErrorCode;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.application.service.FailApprovePaymentService;
import com.commerce.payment.application.service.SucceedPaymentApprovalService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * provider 중립 승인 확정 조율 facade. tx를 열지 않는다(@Component).
 * 검증된 APPROVE Payment를 받아 succeedApproval 시도 → 성공이면 확정, 거부면 errorCode 기반 보상 → Outcome 반환.
 * 결합은 이 facade 한 점에만 격리한다 — 거부 사유는 errorCode로 받으며 주문 상태 재조회 분기 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmApprovalUseCase {

	private final SucceedPaymentApprovalService succeedPaymentApprovalService;
	private final CompensateApprovalUseCase compensateApprovalUseCase;
	private final FailApprovePaymentService failApprovePaymentService;
	private final PaymentRepository paymentRepository;
	private final NotificationPort notificationPort;

	/**
	 * 승인 확정을 시도하고 결과를 Outcome으로 반환한다.
	 * verifyApprovedResponse 검증도 이 메서드 안에서 수행한다 — 검증 실패도 보상 분기로 흐르도록 보장한다.
	 *
	 * @param payment                  확정할 APPROVE Payment
	 * @param now                      처리 시각
	 * @param pgCanceller              보상 환불에 사용할 PG 취소 콜백 (진입점별로 다름)
	 * @param responseMerchantPayKey   PG 응답 merchantPayKey (merchantPayKey 불일치 검증에 필요)
	 * @param responseTotalAmount      PG 응답 금액 (금액 불일치 보상에 필요)
	 * @return Outcome — 성공/거부(errorCode 보존)/예외 전파
	 */
	public Outcome confirm(Payment payment, LocalDateTime now, PgCanceller pgCanceller,
		String responseMerchantPayKey, int responseTotalAmount) {
		try {
			payment.verifyApprovedResponse(responseMerchantPayKey, responseTotalAmount);
			succeedPaymentApprovalService.succeedApproval(payment, now);
			return Outcome.succeeded();
		} catch (OrderException ex) {
			return handleOrderRejection(payment, now, pgCanceller, ex);
		} catch (PaymentException ex) {
			return handlePaymentRejection(payment, now, pgCanceller, responseTotalAmount, ex);
		}
	}

	private Outcome handleOrderRejection(Payment payment, LocalDateTime now, PgCanceller pgCanceller, OrderException ex) {
		ErrorCode code = ex.getErrorCode();

		if (code == OrderErrorCode.ORDER_CANCELED_FOR_PAYMENT) {
			// CANCELED 주문 — 보상 환불 + 통지
			compensateApprovalUseCase.compensateCanceledOrderApproval(payment, pgCanceller);
			log.warn("승인 확정 - 취소 주문 보상 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return Outcome.rejected(PaymentErrorCode.PAYMENT_DUPLICATE);
		}

		if (code == OrderErrorCode.ORDER_ALREADY_PAID) {
			return handleAlreadyPaidRejection(payment, now, pgCanceller,
				new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE));
		}

		if (code == OrderErrorCode.ORDER_INVALID_STATE_FOR_PAYMENT) {
			// 그 외 비-INIT 주문 — 환불 없이 FAILED 종착
			log.error("승인 확정 - 비-INIT 주문 상태로 종착 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			failApprovePaymentService.fail(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
				PaymentFailCode.APPROVE_PROCESS_FAILED, "비-INIT 주문 상태: " + code, now
			);
			return Outcome.rejected(PaymentErrorCode.PAYMENT_APPROVE_FAILED);
		}

		if (code == OrderErrorCode.ORDER_NOT_FOUND) {
			// 주문 자체 없음 — 정합성 오류, 환불 없이 통지 + FAILED 종착 (새 상태를 도입하지 않는 escalation 종착·통지 결정을 따른다)
			log.error("승인 확정 - 주문 없음(정합성 오류) paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			failApprovePaymentService.fail(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
				PaymentFailCode.APPROVE_PROCESS_FAILED, "주문 없음 - 정합성 오류", now
			);
			notifyInconsistency(payment, "주문 없음 - 정합성 오류");
			return Outcome.rejected(PaymentErrorCode.PAYMENT_APPROVE_FAILED);
		}

		// 그 외 OrderException은 보상 없이 전파
		return Outcome.propagate(ex);
	}

	private Outcome handlePaymentRejection(Payment payment, LocalDateTime now, PgCanceller pgCanceller,
		int responseTotalAmount, PaymentException ex) {

		ErrorCode code = ex.getErrorCode();

		if (code == PaymentErrorCode.PAYMENT_DUPLICATE) {
			return handleAlreadyPaidRejection(payment, now, pgCanceller, ex);
		}

		if (code == PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH) {
			compensateApprovalUseCase.compensateMerchantKeyMismatch(payment);
			log.warn("승인 확정 - merchantPayKey 불일치 보상 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return Outcome.rejected(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
		}

		if (code == PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH) {
			compensateApprovalUseCase.compensateAmountMismatch(payment, responseTotalAmount, pgCanceller);
			log.warn("승인 확정 - 금액 불일치 보상 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return Outcome.rejected(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}

		// 보상 비대상 PaymentException(예: 정상 승인 후 기록 실패) — 보상 없이 전파
		return Outcome.propagate(ex);
	}

	/**
	 * ORDER_ALREADY_PAID / PAYMENT_DUPLICATE 거부 처리.
	 * existsApprovedByOrderId로 실제 중복 여부를 판별해 분기한다.
	 */
	private Outcome handleAlreadyPaidRejection(Payment payment, LocalDateTime now, PgCanceller pgCanceller,
		RuntimeException cause) {

		boolean hasDuplicateSucceeded = paymentRepository.existsApprovedByOrderId(payment.getOrderId());
		if (hasDuplicateSucceeded) {
			// 중복 결제 — 보상 환불
			log.warn("승인 확정 - 중복 결제 보상 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			compensateApprovalUseCase.compensateDuplicatePayment(payment, cause, pgCanceller);
			return Outcome.rejected(PaymentErrorCode.PAYMENT_DUPLICATE);
		}

		// 비중복 PAID — dead 경로지만 만약 도달하면 환불하지 않고 통지 + FAILED 종착 (금전 정합성)
		log.error("승인 확정 - 비중복 PAID 비정상 상태(정합성 오류) paymentId={} orderId={} merchantPayKey={}",
			payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
		failApprovePaymentService.fail(
			payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
			PaymentFailCode.APPROVE_PROCESS_FAILED, "PAID 주문이나 SUCCEEDED APPROVE 없음 - 정합성 오류", now
		);
		notifyInconsistency(payment, "PAID 주문 비중복 정합성 오류");
		return Outcome.rejected(PaymentErrorCode.PAYMENT_APPROVE_FAILED);
	}

	private void notifyInconsistency(Payment payment, String reason) {
		try {
			notificationPort.notifyManualReviewRequired(
				payment.getOrderId(), payment.getMerchantPayKey(), reason);
		} catch (Exception ex) {
			log.warn("정합성 오류 통지 전송 실패 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey(), ex);
		}
	}

	/** facade 처리 결과 종류. */
	public enum Decision { SUCCEEDED, REJECTED, PROPAGATE }

	/**
	 * facade 처리 결과. 진입점이 응답/종착으로 번역한다.
	 * - REJECTED면 errorCode 보존 — 실시간 진입점이 이 errorCode로 PaymentException을 다시 throw한다.
	 * - PROPAGATE면 보상 비대상 예외(예: 정상 승인 후 기록 실패) — 호출부가 cause를 그대로 rethrow한다.
	 */
	public record Outcome(Decision decision, PaymentErrorCode errorCode, RuntimeException cause) {

		public static Outcome succeeded() {
			return new Outcome(Decision.SUCCEEDED, null, null);
		}

		public static Outcome rejected(PaymentErrorCode errorCode) {
			return new Outcome(Decision.REJECTED, errorCode, null);
		}

		public static Outcome propagate(RuntimeException cause) {
			return new Outcome(Decision.PROPAGATE, null, cause);
		}
	}
}
