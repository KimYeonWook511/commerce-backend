package com.commerce.payment.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.common.exception.ErrorCode;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.payment.application.dto.ApprovalOutcome;
import com.commerce.payment.application.dto.RejectionAnomaly;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.exception.PaymentErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 승인이 났는데 우리가 그 결제를 이어갈 수 없을 때 결제를 종결한다. 승인 요청 흐름과 대사가 이 자리를
 * 공유하므로 종결 코드가 한 벌만 존재한다.
 *
 * <p>반려는 되돌릴 환불까지 따르고, 무엇 때문에 반려하는지가 환불 사유로 갈린다. 결제 키가 어긋난
 * 경우만 환불을 만들지 않는다 — 나간 돈은 그 키의 주인 것이라 우리가 되돌릴 대상이 아니다.
 *
 * <p>트랜잭션을 열지 않는다. 종결마다 단위작업 하나가 커밋되고, 통지는 그 커밋 뒤에 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClosePaymentUseCase {

	private final PaymentService paymentService;
	private final NotificationPort notificationPort;

	/** 결제사가 승인한 금액이 우리가 기록한 결제 금액과 다르다. 되돌릴 금액은 결제사가 승인한 쪽이다 */
	public ApprovalOutcome rejectAmountMismatch(Payment payment, int approvedAmount, String pgTransactionId) {
		RejectionAnomaly anomaly = paymentService.reject(
			payment.getId(),
			PaymentCloseCode.AMOUNT_MISMATCH,
			"승인 금액 " + approvedAmount + ", 주문 금액 " + payment.getAmount(),
			approvedAmount,
			pgTransactionId,
			RefundReason.AMOUNT_MISMATCH
		);
		notifyIfAnomalous(payment, anomaly);
		return ApprovalOutcome.rejected(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
	}

	/**
	 * 회원이 결제창에 머문 사이 주문이 그 결제를 받을 수 없는 상태가 됐다. 거부 사유는 확정 시도가 던진
	 * 오류 코드로 받는다 — 주문을 다시 조회하면 그 사이 상태가 또 바뀔 수 있다.
	 */
	public ApprovalOutcome rejectOrderNotPayable(
		Payment payment,
		ErrorCode orderErrorCode,
		int approvedAmount,
		String pgTransactionId
	) {
		RejectionAnomaly anomaly = paymentService.reject(
			payment.getId(),
			PaymentCloseCode.ORDER_NOT_PAYABLE,
			"주문 상태로 확정할 수 없다: " + orderErrorCode.getCode(),
			approvedAmount,
			pgTransactionId,
			RefundReason.ORDER_NOT_PAYABLE
		);
		notifyIfAnomalous(payment, anomaly);
		return ApprovalOutcome.rejected(toMemberFacingCode(orderErrorCode));
	}

	/**
	 * 승인은 났으나 우리가 모르는 경로로 이미 취소됐다. 돈이 이미 돌아갔으므로 환불을 만들지 않고,
	 * 닫지 않으면 그 결제가 활성 슬롯을 쥔 채 남아 그 주문을 영영 결제할 수 없다.
	 *
	 * <p>되돌릴 것이 없고 조사만 남는 일이라 통지는 이 자리에서 한 번이다.
	 */
	public ApprovalOutcome closeExternallyCanceled(
		Payment payment,
		PgHistoryEntry approval,
		PgHistoryEntry canceled
	) {
		paymentService.failExternallyCanceled(
			payment.getId(),
			"밖에서 취소됨 시각=" + canceled.occurredAt() + " 금액=" + canceled.amount(),
			approval.amount(),
			approval.pgTransactionId()
		);
		notificationPort.notifyManualReviewRequired(
			payment.getOrderId(), payment.getPaymentKey(), "우리가 모르는 경로로 취소된 승인");
		return ApprovalOutcome.rejected(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
	}

	/**
	 * 승인 응답에 실려 온 결제 키가 우리 것이 아니다. 그 응답에는 상대의 결제 키와 결제사 번호가 들어
	 * 있고, 그것은 상대의 결제가 방금 승인되어 돈이 나갔다는 뜻이다.
	 *
	 * <p>우리 결제를 종결하고 상대의 결제를 회수하는 것까지가 한 트랜잭션이다. 확정은 하지 않는다 —
	 * 상대의 주문이 살아 있는지에 따라 갈리는 판정은 승인 확정 흐름의 몫이다.
	 */
	public ApprovalOutcome closeKeyMismatch(Payment payment, String responsePaymentKey, String pgPaymentId) {
		paymentService.failAndReclaim(payment.getId(), responsePaymentKey, pgPaymentId);
		// 정상 운영에서 나올 수 없는 조합이라 사람이 봐야 한다. 담는 값은 두 결제 키와 결제사 번호까지다.
		notificationPort.notifyManualReviewRequired(
			payment.getOrderId(),
			payment.getPaymentKey(),
			"승인 응답의 결제 키가 다르다 responsePaymentKey=" + responsePaymentKey + " pgPaymentId=" + pgPaymentId
		);
		return ApprovalOutcome.rejected(PaymentErrorCode.PAYMENT_KEY_MISMATCH);
	}

	/**
	 * 반려가 남긴 정합성 이상을 알린다. 커밋이 끝난 이 자리에서 보내는 것이 중요하다 — 트랜잭션 안에서
	 * 던지면 방금 만든 되돌릴 근거가 통째로 롤백된다.
	 */
	private void notifyIfAnomalous(Payment payment, RejectionAnomaly anomaly) {
		if (!anomaly.isAnomalous()) {
			return;
		}
		notificationPort.notifyManualReviewRequired(
			payment.getOrderId(), payment.getPaymentKey(), anomaly.description());
	}

	private PaymentErrorCode toMemberFacingCode(ErrorCode orderErrorCode) {
		if (orderErrorCode == OrderErrorCode.ORDER_ALREADY_PAID) {
			return PaymentErrorCode.PAYMENT_DUPLICATE;
		}
		return PaymentErrorCode.PAYMENT_APPROVAL_FAILED;
	}
}
