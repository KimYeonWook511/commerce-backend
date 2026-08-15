package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.service.PgCallLogService;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 환불 하나를 결제사에 보낸다. 주문 취소·승인 반려·발송 배치·대사가 이 자리를 공유하므로 보내는 규칙이
 * 한 벌만 존재한다.
 *
 * <p>들어오는 문이 둘이다. 아직 안 나간 건을 처음 보내는 것과, 이력에 그 시도가 없음을 확인한 대사가
 * 같은 키로 다시 보내는 것이다. 앞엣것은 부르기 직전에 상태를 옮기며 시도 번호를 올리고, 뒤엣것은 상태와
 * 번호를 그대로 둔 채 부른 시각만 갱신한다. 그 뒤의 호출·기록·결과 반영은 같은 자리를 지난다.
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
	private final RefundRepository refundRepository;

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
	 * 이력에 그 시도가 없음을 확인한 대사가 그 자리에서 다시 보낸다.
	 *
	 * <p>상태를 되돌리지 않는다. 되돌렸다가 나중에 보내면 그 사이 결제사가 이력에 반영할 수 있어 보내기
	 * 직전에 또 읽어야 하는데, 같은 자리에서 읽고 보내면 그 창이 트랜잭션 둘 사이로 줄어든다.
	 *
	 * <p>시도 번호를 올리지 않아 같은 키가 나간다. 결과를 모르는 건이라 이미 나갔을 수 있고, 새 키로
	 * 보내면 완료된 취소 위에 또 하나가 실행된다. 같은 키면 이미 나갔을 때 이전 응답이 되돌아와 그대로
	 * 확정된다. 대신 부른 시각은 갱신한다 — 대사 유예를 그 값으로 재기 때문이다.
	 *
	 * @return 이 호출을 마친 시점의 환불 진행 상태
	 */
	public RefundStatus resend(Payment payment, Refund refund, PgCallSource source) {
		Refund calling;
		try {
			calling = refundService.recordRequested(refund.getId(), LocalDateTime.now());
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.REFUND_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED) {
				// 그 사이 다른 주체가 결과를 확정했거나 같은 건을 먼저 집었다.
				log.info("다시 부르기 직전 전이에 밀려 환불을 부르지 않는다 refundId={} 사유={}",
					refund.getId(), ex.getErrorCode());
				return refund.getStatus();
			}
			throw ex;
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
			return apply(payment, refund, result);
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
	private RefundStatus apply(Payment payment, Refund refund, PgRefundResult result) {
		return switch (result.outcome()) {
			case SUCCEEDED -> transition(refund,
				() -> refundService.complete(refund.getId(), result.pgTransactionId()), RefundStatus.SUCCEEDED);
			case UNKNOWN -> transition(refund,
				() -> refundService.markUnknown(refund.getId()), RefundStatus.UNKNOWN);
			case RETRYABLE_FAILURE -> transition(refund,
				() -> refundService.recordRetryableFailure(refund.getId()), RefundStatus.IN_PROGRESS);
			case TERMINAL_FAILURE -> applyTerminalFailure(payment, refund, result);
		};
	}

	/**
	 * 환불 가능 금액을 넘는다는 거절만 상태를 바로 정하지 않고 이력을 읽는다. 우리 한도 검사를 이미
	 * 통과한 요청이므로 그 거절은 우리 기록과 결제사 기록이 갈렸다는 뜻이고, 어느 쪽이 맞는지 확인하지
	 * 않고 사람에게 넘기면 저절로 풀릴 건까지 손처리 대상이 된다.
	 *
	 * <p>잔여가 0이라 "이미 취소된 결제"로 오는 답도 같은 검토 코드로 접혀 이 갈래에 함께 들어온다 —
	 * 결제사가 어느 코드로 답할지는 검증 순서에 달렸고 명세에 없다.
	 */
	private RefundStatus applyTerminalFailure(Payment payment, Refund refund, PgRefundResult result) {
		if (result.reviewCode() == RefundReviewCode.REFUNDABLE_AMOUNT_EXCEEDED) {
			return settleAgainstHistory(payment, refund, result);
		}
		return transition(refund,
			() -> refundService.flagForReview(refund.getId(), result.reviewCode(), result.message()),
			RefundStatus.MANUAL_REVIEW);
	}

	/**
	 * 초과 거절을 이력으로 세 갈래로 가른다. 가르는 근거는 이력의 취소 항목이 우리 환불 기록으로
	 * 설명되는지다 — 우리 시도 키가 실린 항목은 우리 사건이고, 실리지 않은 항목은 우리가 모르는 환불이다.
	 *
	 * <ol>
	 *   <li>이 사건이 이미 완료로 있다 — 성공으로 확정한다.
	 *   <li>결과를 모르던 다른 건이 완료된 것으로 설명된다 — 그 건을 확정하고 이번 건은 상태를 그대로 둔다.
	 *       다음 주기의 대사가 같은 키로 다시 보내며, 한도는 다시 검사하지 않는다 — 누적 환불액은 환불을
	 *       만들 때만 오르므로 만들 때 통과한 이 환불은 지금도 한도 안이고, 재검사를 두면 없앤 합계 조회가
	 *       그 자리로 되살아난다.
	 *   <li>어느 쪽으로도 설명되지 않는다 — 우리가 모르는 환불이 있다. 사람에게 넘긴다.
	 * </ol>
	 *
	 * <p>다시 보내도 중복이 되지 않는다. 거절된 요청은 결제사가 실행하지 않아 이력에 흔적이 없다.
	 */
	private RefundStatus settleAgainstHistory(Payment payment, Refund refund, PgRefundResult result) {
		PgHistoryResult history = paymentGatewayPort.readHistory(payment, PgHistoryScope.REFUND_ONLY);
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			// 묻지 못한 것을 "설명되지 않는다"로 읽으면 인증 설정이 틀린 순간 멀쩡한 환불이 전부 손처리
			// 대상이 된다. 확정하지 않고 다음 주기에 다시 집게 둔다.
			log.warn("초과 거절의 원인을 이력으로 가르지 못해 확정하지 않는다 refundId={} 사유={}",
				refund.getId(), history.message());
			return refund.getStatus();
		}

		Optional<PgHistoryEntry> ours = history.settledRefundOf(refund);
		if (ours.isPresent()) {
			return transition(refund,
				() -> refundService.complete(refund.getId(), ours.get().pgTransactionId()), RefundStatus.SUCCEEDED);
		}
		if (settleOthersExplainedBy(payment, refund, history)) {
			log.info("결과를 모르던 다른 환불이 초과 거절을 설명해 이번 건은 다음 주기로 넘긴다 refundId={}",
				refund.getId());
			return refund.getStatus();
		}
		return transition(refund,
			() -> refundService.flagForReview(
				refund.getId(), RefundReviewCode.REFUNDABLE_AMOUNT_EXCEEDED, result.message()),
			RefundStatus.MANUAL_REVIEW);
	}

	/**
	 * 같은 결제에서 결과를 모르던 다른 환불이 이력에 완료로 있으면 그것을 확정한다.
	 *
	 * @return 그렇게 확정된 건이 하나라도 있으면 true. 그것이 곧 어긋남의 원인이 밝혀졌다는 뜻이다
	 */
	private boolean settleOthersExplainedBy(Payment payment, Refund refund, PgHistoryResult history) {
		boolean explained = false;
		for (Refund sibling : refundRepository.findUnsettledByPaymentId(payment.getId())) {
			if (sibling.getId().equals(refund.getId())) {
				continue;
			}
			Optional<PgHistoryEntry> settled = history.settledRefundOf(sibling);
			if (settled.isEmpty()) {
				continue;
			}
			try {
				refundService.complete(sibling.getId(), settled.get().pgTransactionId());
				explained = true;
			} catch (PaymentException ex) {
				// 다른 주체가 먼저 옮겼다. 그래도 그 건이 완료라는 사실은 달라지지 않으므로 설명된 것으로 센다.
				log.info("다른 주체가 먼저 옮겨 이번 확정을 반영하지 않는다 refundId={} 사유={}",
					sibling.getId(), ex.getErrorCode());
				explained = true;
			}
		}
		return explained;
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
