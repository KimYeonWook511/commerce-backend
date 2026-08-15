package com.commerce.payment.application.port.dto;

import com.commerce.payment.domain.RefundReviewCode;

/**
 * 환불 호출의 결과. 결제사 어휘가 없다 — 어댑터가 접은 갈래와 우리 값으로 옮긴 항목만 담는다.
 *
 * <p>다시 시도할 수 없는 실패에만 검토 코드가 실린다. 관리자가 알아야 하는 것은 결국 돈이 나갔는가이고
 * 그 값이 상태보다 정확히 알려주는데, 어느 결과 코드가 어느 검토 코드인지는 결제사마다 달라 어댑터가
 * 정한다.
 *
 * @param outcome         네 갈래 중 하나
 * @param answered        결제사가 답을 돌려줬나. 갈래가 "모른다"일 때 다음에 할 일이 이 값으로 갈린다
 * @param reviewCode      왜 자동으로 더 못 하게 됐나. 다시 시도할 수 없는 실패일 때만 있다
 * @param pgTransactionId 결제사가 발급한 취소 거래 번호. 판정에는 쓰지 않고 정산 대조·추적에 쓴다
 * @param message         결제사가 준 문구. 검토 상세에 남기며 분기에 쓰지 않는다
 * @param callRecord      호출 기록에 담을 값
 */
public record PgRefundResult(
	PgOutcome outcome,
	boolean answered,
	RefundReviewCode reviewCode,
	String pgTransactionId,
	String message,
	PgCallRecord callRecord
) {

	public static PgRefundResult succeeded(String pgTransactionId, String message, PgCallRecord callRecord) {
		return new PgRefundResult(PgOutcome.SUCCEEDED, true, null, pgTransactionId, message, callRecord);
	}

	/** 답은 받았는데 그 답이 결과를 정하지 못했다. 이력을 읽으면 풀리는 자리다 */
	public static PgRefundResult unsettled(String message, PgCallRecord callRecord) {
		return new PgRefundResult(PgOutcome.UNKNOWN, true, null, null, message, callRecord);
	}

	/** 답을 받지 못했다. 취소가 처리됐을 수 있어 실패로 단정하지 않는다 */
	public static PgRefundResult unanswered(String message, PgCallRecord callRecord) {
		return new PgRefundResult(PgOutcome.UNKNOWN, false, null, null, message, callRecord);
	}

	/** 시간이 지나거나 앞선 취소가 끝나면 풀린다. 환불 상태를 그대로 두고 다음 주기에 다시 보낸다 */
	public static PgRefundResult retryableFailure(boolean answered, String message, PgCallRecord callRecord) {
		return new PgRefundResult(PgOutcome.RETRYABLE_FAILURE, answered, null, null, message, callRecord);
	}

	/** 같은 요청을 다시 보내도 같은 답이 온다. 사람이 이어받아야 한다 */
	public static PgRefundResult terminalFailure(
		RefundReviewCode reviewCode,
		String message,
		PgCallRecord callRecord
	) {
		return new PgRefundResult(PgOutcome.TERMINAL_FAILURE, true, reviewCode, null, message, callRecord);
	}
}
