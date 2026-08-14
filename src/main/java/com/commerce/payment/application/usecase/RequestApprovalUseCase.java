package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.commerce.payment.application.dto.ApprovalOutcome;
import com.commerce.payment.application.dto.ApprovalResult;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.application.service.PgCallLogService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원이 결제창에서 돌아왔다. 결제사에 승인을 요청하고, 응답을 검증해 확정 흐름으로 넘긴다.
 *
 * <p>트랜잭션을 열지 않는다. 결제사 호출이 트랜잭션 안에 있으면 행 락을 쥔 채 외부 응답을 기다리게
 * 되고, 부르기 직전 전이의 낙관 락 충돌도 트랜잭션 밖에서 받아야 물러날 수 있다.
 *
 * <p>확정하지 못한 경우는 모두 "결과 확인 중"으로 답한다. 성공으로 답하면 잡히지 않은 주문을 회원이
 * 기다리게 되고, 실패로 답하면 회원이 다시 결제해 돈이 두 번 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestApprovalUseCase {

	private final PaymentRepository paymentRepository;
	private final PaymentGatewayPort paymentGatewayPort;
	private final PaymentService paymentService;
	private final PgCallLogService pgCallLogService;
	private final ConfirmApprovalUseCase confirmApprovalUseCase;
	private final ClosePaymentUseCase closePaymentUseCase;
	private final NotificationPort notificationPort;

	public ApprovalResult approve(Long memberId, String paymentKey, String pgPaymentId) {
		// 결제 키와 회원으로 함께 좁힌다. 남의 결제 키를 실으면 그 번호가 있는지조차 드러나지 않는다.
		Payment payment = paymentRepository.findByPaymentKeyAndMemberId(paymentKey, memberId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		switch (payment.getStatus()) {
			case SUCCEEDED -> {
				return ApprovalResult.succeeded(payment.getPgPaymentId());
			}
			// 이미 부른 뒤라 결과를 기다리는 중이다. 여기서 다시 부르면 같은 결제에 승인이 두 번 나간다.
			case IN_PROGRESS, UNKNOWN -> throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			// 우리가 종결해도 결제사 쪽 예약은 살아 있어 옛 결제창의 인증이 여기로 돌아온다.
			case FAILED, REJECTED, EXPIRED -> throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_CLOSED);
			case READY -> {
				// 아래로 이어간다.
			}
		}

		Payment calling = beginApproval(payment, pgPaymentId);
		PgCallLog callLog = beginCallLog(calling);

		PgApproveResult result = paymentGatewayPort.approve(calling);
		try {
			return resolve(calling, result);
		} finally {
			recordCallResult(callLog, result);
		}
	}

	/**
	 * 부르기 직전 전이를 따로 커밋한다. 결제사 번호를 여기서 심지 않으면 응답을 못 받았을 때 그 승인을
	 * 가리키는 값이 우리 쪽에 하나도 없어, 대사가 이력을 읽으려 해도 열쇠가 없다.
	 *
	 * <p>이 커밋에 지면 부르지 않는다. 진 것이 곧 "이긴 쪽이 지금 부르고 있다"는 뜻이라 결과를 모르는
	 * 상황이며, 그때의 응답을 그대로 쓴다.
	 */
	private Payment beginApproval(Payment payment, String pgPaymentId) {
		try {
			return paymentService.markInProgress(payment.getId(), pgPaymentId, LocalDateTime.now());
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED) {
				log.info("부르기 직전 전이에 밀려 결제사를 부르지 않는다 paymentId={} 사유={}",
					payment.getId(), ex.getErrorCode());
				throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			}
			throw ex;
		}
	}

	/**
	 * 호출 기록을 부르기 직전에 만든다. 이 저장이 실패하면 부르지 않는다 — 기록 없이 부르면 "불렀는지
	 * 알 수 있게 한다"는 목적이 통째로 무너진다. 전이는 이미 커밋돼 있으므로 그 건은 대사가 이력을
	 * 읽어 회수한다.
	 */
	private PgCallLog beginCallLog(Payment payment) {
		try {
			return pgCallLogService.startApproveCall(
				payment.getId(), payment.pgIdempotencyKey(), LocalDateTime.now());
		} catch (RuntimeException ex) {
			log.error("승인 호출 기록을 남기지 못해 결제사를 부르지 않는다 paymentId={}", payment.getId(), ex);
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		}
	}

	/** 기록이 승인 판정을 흔들지 않는다. 판정의 정본은 이미 커밋됐고 이 행은 조사에서만 읽는다 */
	private void recordCallResult(PgCallLog callLog, PgApproveResult result) {
		try {
			pgCallLogService.recordResult(callLog, LocalDateTime.now(), result.callRecord());
		} catch (RuntimeException ex) {
			log.error("승인 호출 결과를 기록하지 못했다 pgCallLogId={}", callLog.getId(), ex);
		}
	}

	private ApprovalResult resolve(Payment payment, PgApproveResult result) {
		return switch (result.outcome()) {
			case SUCCEEDED -> verifyAndConfirm(payment, result);
			// 답은 받았는데 그 답이 결과를 정하지 못했다. 이력을 읽으면 풀린다.
			case UNKNOWN -> result.answered() ? resolveFromHistory(payment) : leaveUnknown(payment, result.message());
			// 요청 흐름의 첫 호출이라 앞선 승인이 없다. 종결해 슬롯을 풀어야 회원이 다시 결제할 수 있다.
			case RETRYABLE_FAILURE -> closeFailed(payment, PaymentCloseCode.PG_UNAVAILABLE, result.message());
			case TERMINAL_FAILURE -> closeFailed(payment, PaymentCloseCode.PG_DECLINED, result.message());
		};
	}

	private ApprovalResult verifyAndConfirm(Payment payment, PgApproveResult result) {
		if (!payment.getPaymentKey().equals(result.paymentKey())) {
			// 그 응답에는 상대의 결제 키와 결제사 번호가 들어 있고, 상대의 결제가 방금 승인됐다는 뜻이다.
			ApprovalOutcome outcome = closePaymentUseCase.closeKeyMismatch(
				payment, result.paymentKey(), payment.getPgPaymentId());
			throw new PaymentException(outcome.errorCode());
		}
		if (!ownsMemberKey(payment, result.memberKey())) {
			// 결제 키는 맞는데 회원만 어긋났다. 두 기록 중 하나가 틀린 상태라 자동으로 정할 근거가 없다 —
			// 종결하면 슬롯이 풀려 돈이 두 번 나갈 수 있고, 되돌리면 그 돈의 주인을 단정하는 것이 된다.
			notificationPort.notifyManualReviewRequired(
				payment.getOrderId(), payment.getPaymentKey(), "승인 응답의 회원이 결제 행의 회원과 다르다");
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		}
		return toResult(payment,
			confirmApprovalUseCase.confirm(payment, result.approvedAmount(), result.pgTransactionId()));
	}

	/**
	 * 결제 예약 때 보낸 회원 식별자가 응답에 실려 온다. 값이 없으면 대조할 것이 없어 통과시킨다 —
	 * 결제창을 여는 쪽이 그 값을 싣지 않으면 응답에도 담기지 않는다.
	 */
	private boolean ownsMemberKey(Payment payment, String memberKey) {
		return !StringUtils.hasText(memberKey) || memberKey.equals(String.valueOf(payment.getMemberId()));
	}

	private ApprovalResult resolveFromHistory(Payment payment) {
		// 승인 판정은 걸러 받지 않는다. 승인이 성립했는지와 그 뒤 취소됐는지를 함께 봐야 한다.
		PgHistoryResult history = paymentGatewayPort.readHistory(payment, PgHistoryScope.ALL);
		return toResult(payment, confirmApprovalUseCase.confirmFromHistory(payment, history));
	}

	private ApprovalResult toResult(Payment payment, ApprovalOutcome outcome) {
		return switch (outcome.decision()) {
			case SUCCEEDED -> ApprovalResult.succeeded(payment.getPgPaymentId());
			case REJECTED -> throw new PaymentException(outcome.errorCode());
			case UNRESOLVED -> leaveUnknown(payment, "승인 결과를 확정하지 못했다");
		};
	}

	/**
	 * 결과를 모른 채 요청 흐름을 끝낸다. 그 자리에서 승인을 다시 부르지 않는다 — 결제사가 원천사로
	 * 승인을 보내 놓고 기다리는 중일 수 있어 가장 위험한 구간이다.
	 */
	private ApprovalResult leaveUnknown(Payment payment, String reason) {
		try {
			paymentService.markUnknown(payment.getId());
		} catch (PaymentException ex) {
			// 그 사이 다른 주체가 그 결제를 옮겼다. 결과를 모르는 것은 그대로라 응답은 같다.
			log.info("결과 불명 표시를 건너뛴다 paymentId={} 사유={}", payment.getId(), ex.getErrorCode());
		}
		log.info("승인 결과를 모른 채 응답한다 paymentId={} 사유={}", payment.getId(), reason);
		throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
	}

	private ApprovalResult closeFailed(Payment payment, PaymentCloseCode closeCode, String message) {
		paymentService.fail(payment.getId(), closeCode, message);
		throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
	}
}
