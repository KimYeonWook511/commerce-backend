package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.service.PgCallLogService;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 환불 하나를 결제사에 보낸다. 주문 취소·승인 반려·발송 배치가 이 자리를 공유하므로 보내는 규칙이 한
 * 벌만 존재한다.
 *
 * <p>순서가 정해져 있다 — 이력 확인, 부르기 직전 전이 커밋, 호출 기록, 결제사 호출, 결과 반영이다.
 * 전이를 부르기 전에 따로 커밋하지 않으면 둘이 같은 환불을 집어 함께 보내고, 부르는 도중 프로세스가
 * 죽었을 때 한 번도 안 부른 건과 구분되지 않는다.
 *
 * <p>트랜잭션을 열지 않는다. 결제사 호출이 트랜잭션 안에 있으면 행 락을 쥔 채 외부 응답을 기다리게
 * 되고, 부르기 직전 전이의 낙관 락 충돌도 트랜잭션 밖에서 받아야 물러날 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteRefundUseCase {

	private final PaymentGatewayPort paymentGatewayPort;
	private final RefundService refundService;
	private final PgCallLogService pgCallLogService;

	/**
	 * 아직 안 나간 환불을 처음 보낸다. 부를 준비 상태일 때만 보내는 것이 겹친 호출을 막는 장치다 —
	 * 이미 응답 대기이면 다른 쪽이 집은 것이라 이 자리에서 부르지 않는다.
	 *
	 * <p>한 번이라도 부른 적이 있는 건은 이력을 먼저 읽어 그 시도가 없음을 확인한 뒤에만 보낸다.
	 * 시도 번호가 0인 건은 나간 적이 없어 읽지 않고 바로 나간다 — 요청 흐름이 커밋하고 죽은 경우라
	 * 늦출 이유가 없다.
	 *
	 * @return 이 호출을 마친 시점에 회원에게 알릴 환불 진행 상태
	 */
	public RefundStatus send(Payment payment, Refund refund, PgCallSource source) {
		if (refund.getStatus() != RefundStatus.REQUESTED) {
			// 다른 쪽이 이미 집었거나 결과가 났다. 결과를 모르는 건을 다시 보내는 것은 이력을 읽은
			// 대사의 몫이라 여기서 하지 않는다.
			log.info("보낼 차례가 아닌 환불이라 부르지 않는다 refundId={} status={}",
				refund.getId(), refund.getStatus());
			return refund.getStatus();
		}
		if (refund.getAttemptSeq() != 0 && !confirmedNotSent(payment, refund)) {
			return refund.getStatus();
		}

		Refund calling = beginCall(refund);
		if (calling == null) {
			// 진 쪽은 부르지 않는다. 이긴 쪽이 지금 보내고 있으므로 회원이 잃는 것이 없다.
			return RefundStatus.IN_PROGRESS;
		}
		return dispatch(payment, calling, source);
	}

	/**
	 * 이 사건의 시도가 이력에 없음을 확인한다. 확인이 서야만 보낸다 — 이 검사가 이중환불을 막는 장치다.
	 *
	 * <p>이력 조회 자체가 거절된 경우도 확인이 선 것으로 보지 않는다. "목록이 비었다"와 "묻지 못했다"를
	 * 같게 다루면 인증 설정이 틀린 순간 이미 나간 취소를 한 번 더 보내게 된다.
	 *
	 * @return 이력에 그 시도가 없음을 확인했으면 true
	 */
	private boolean confirmedNotSent(Payment payment, Refund refund) {
		PgHistoryResult history = paymentGatewayPort.readHistory(payment, PgHistoryScope.REFUND_ONLY);
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			log.warn("이력을 읽지 못해 환불을 보내지 않는다 refundId={} 사유={}", refund.getId(), history.message());
			return false;
		}
		boolean found = history.entries().stream()
			.anyMatch(entry -> refund.ownsHistoryEntry(entry.refundAttemptKey()));
		if (found) {
			log.warn("이미 나간 시도가 이력에 있어 다시 보내지 않는다 refundId={} attemptSeq={}",
				refund.getId(), refund.getAttemptSeq());
		}
		return !found;
	}

	/**
	 * 부르기 직전 전이를 따로 커밋한다. 이 커밋에 지면 부르지 않는다 — 진 것이 곧 "다른 쪽이 이미
	 * 집었다"는 뜻이고, 그래도 부르면 같은 환불이 두 번 나간다.
	 *
	 * @return 집은 환불. 물러났으면 {@code null}
	 */
	private Refund beginCall(Refund refund) {
		try {
			return refundService.markInProgress(refund.getId(), LocalDateTime.now());
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.REFUND_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED) {
				log.info("부르기 직전 전이에 밀려 환불을 부르지 않는다 refundId={} 사유={}",
					refund.getId(), ex.getErrorCode());
				return null;
			}
			throw ex;
		}
	}

	private RefundStatus dispatch(Payment payment, Refund refund, PgCallSource source) {
		PgCallLog callLog = beginCallLog(payment, refund);
		if (callLog == null) {
			// 전이는 이미 커밋돼 있으므로 그 건은 대사가 이력을 읽어 회수한다. 같은 키로 다시 나가므로
			// 번호가 소모되지도 않는다.
			return RefundStatus.IN_PROGRESS;
		}

		PgRefundResult result = paymentGatewayPort.refund(payment, refund, source);
		try {
			return apply(refund, result);
		} finally {
			recordCallResult(callLog, result);
		}
	}

	/**
	 * 호출 기록을 부르기 직전에 만든다. 이 저장이 실패하면 부르지 않는다 — 기록 없이 부르면 "불렀는지
	 * 알 수 있게 한다"는 목적이 통째로 무너진다.
	 */
	private PgCallLog beginCallLog(Payment payment, Refund refund) {
		try {
			return pgCallLogService.startRefundCall(
				payment.getId(), refund.getId(), refund.getPgIdempotencyKey(), LocalDateTime.now());
		} catch (RuntimeException ex) {
			log.error("환불 호출 기록을 남기지 못해 결제사를 부르지 않는다 refundId={}", refund.getId(), ex);
			return null;
		}
	}

	/**
	 * 결제사 답이 어느 전이로 가는지를 정한다. 다시 시도할 수 있는 실패는 상태를 그대로 두고 시도
	 * 번호만 올려 다음 호출이 새 키로 나가게 한다 — 환불에는 실패로 끝나는 종착이 없다.
	 */
	private RefundStatus apply(Refund refund, PgRefundResult result) {
		return switch (result.outcome()) {
			case SUCCEEDED -> transition(refund,
				() -> refundService.complete(refund.getId(), result.pgTransactionId()), RefundStatus.SUCCEEDED);
			case UNKNOWN -> transition(refund,
				() -> refundService.markUnknown(refund.getId()), RefundStatus.UNKNOWN);
			case RETRYABLE_FAILURE -> transition(refund,
				() -> refundService.recordRetryableFailure(refund.getId()), RefundStatus.IN_PROGRESS);
			case TERMINAL_FAILURE -> transition(refund,
				() -> refundService.flagForReview(refund.getId(), result.reviewCode(), result.message()),
				RefundStatus.MANUAL_REVIEW);
		};
	}

	/**
	 * 전이를 커밋하고 그 결과 상태를 돌려준다. 그 사이 다른 주체가 같은 환불을 먼저 옮겼으면 그대로
	 * 둔다 — 돈이 어떻게 됐는지는 대사가 이력으로 확정하고, 회원에게는 어느 쪽이든 "처리 중"이다.
	 */
	private RefundStatus transition(Refund refund, Runnable transition, RefundStatus applied) {
		try {
			transition.run();
			return applied;
		} catch (PaymentException ex) {
			log.info("다른 주체가 먼저 환불을 옮겨 이번 결과를 반영하지 않는다 refundId={} 사유={}",
				refund.getId(), ex.getErrorCode());
			return RefundStatus.IN_PROGRESS;
		}
	}

	/** 기록이 환불 판정을 흔들지 않는다. 판정의 정본은 이미 커밋됐고 이 행은 조사에서만 읽는다 */
	private void recordCallResult(PgCallLog callLog, PgRefundResult result) {
		try {
			pgCallLogService.recordResult(callLog, LocalDateTime.now(), result.callRecord());
		} catch (RuntimeException ex) {
			log.error("환불 호출 결과를 기록하지 못했다 pgCallLogId={}", callLog.getId(), ex);
		}
	}
}
