package com.commerce.payment.application.port.dto;

/**
 * 승인 호출의 결과. 결제사 어휘가 없다 — 어댑터가 접은 갈래와 우리 값으로 옮긴 항목만 담는다.
 *
 * <p>승인이 성립한 경우에만 아래 넷이 채워진다. 그 밖의 갈래에서는 결제사가 값을 주지 않으므로
 * 비어 있고, 무엇을 할지는 갈래가 정한다.
 *
 * @param outcome        네 갈래 중 하나
 * @param answered       결제사가 답을 돌려줬나. 갈래가 "모른다"일 때 다음에 할 일이 이 값으로 갈린다 —
 *                       답을 못 받았으면 그 자리에서 더 물을 것이 없고, 답은 받았는데 그 답이 결과를
 *                       정하지 못한 것이면 이력을 읽어 풀 수 있다
 * @param approvalWindowClosed 결제사가 "승인 가능 시간이 지났다"고 답했나. 그 답은 <b>지금 승인이 없고
 *                       앞으로도 생기지 않는다</b>를 함께 뜻해, 앞선 호출의 결과를 모르는 결제를 실패로
 *                       확정하는 유일한 근거다. 갈래를 늘리지 않고 따로 둔 것은 "이번 요청이 실패했다"도
 *                       같은 갈래로 오는데 그것은 앞선 호출에 대한 답이 아니라 확정 근거가 못 되기
 *                       때문이다. 어느 결과 코드가 이 답인지는 결제사마다 다르고 어댑터가 정한다
 * @param paymentKey     응답에 실려 온 우리 결제 키. 이 승인이 그 결제의 것인지 대조하는 값이다
 * @param memberKey      응답에 실려 온 우리 회원 키. 결제 키만 보던 대조에 한 겹을 더한다
 * @param approvedAmount 결제사가 승인한 금액. 한도 계산의 기준이 되는 값이다
 * @param pgTransactionId 결제사가 발급한 거래 번호. 판정에는 쓰지 않고 정산 대조·조사에 쓴다
 * @param message        결제사가 준 문구. 종결 상세에 남기며 분기에 쓰지 않는다
 * @param callRecord     호출 기록에 담을 값
 */
public record PgApproveResult(
	PgOutcome outcome,
	boolean answered,
	boolean approvalWindowClosed,
	String paymentKey,
	String memberKey,
	Integer approvedAmount,
	String pgTransactionId,
	String message,
	PgCallRecord callRecord
) {

	public static PgApproveResult succeeded(
		String paymentKey,
		String memberKey,
		int approvedAmount,
		String pgTransactionId,
		String message,
		PgCallRecord callRecord
	) {
		return new PgApproveResult(PgOutcome.SUCCEEDED, true, false,
			paymentKey, memberKey, approvedAmount, pgTransactionId, message, callRecord);
	}

	/** 답은 받았는데 그 답이 결과를 정하지 못했다. 이력을 읽으면 풀리는 자리다 */
	public static PgApproveResult unsettled(String message, PgCallRecord callRecord) {
		return new PgApproveResult(PgOutcome.UNKNOWN, true, false, null, null, null, null, message, callRecord);
	}

	/** 답을 받지 못했다. 요청이 처리됐을 수 있어 실패로 단정하지 않는다 */
	public static PgApproveResult unanswered(String message, PgCallRecord callRecord) {
		return new PgApproveResult(PgOutcome.UNKNOWN, false, false, null, null, null, null, message, callRecord);
	}

	public static PgApproveResult retryableFailure(boolean answered, String message, PgCallRecord callRecord) {
		return new PgApproveResult(
			PgOutcome.RETRYABLE_FAILURE, answered, false, null, null, null, null, message, callRecord);
	}

	public static PgApproveResult terminalFailure(boolean answered, String message, PgCallRecord callRecord) {
		return new PgApproveResult(
			PgOutcome.TERMINAL_FAILURE, answered, false, null, null, null, null, message, callRecord);
	}

	/**
	 * 승인 가능 시간이 지났다는 답. 앞으로도 승인이 생기지 않는다는 뜻이라 그 결제를 실패로 확정할 수
	 * 있는 유일한 답이며, 다시 불러도 같은 답이 오므로 다시 시도할 수 없는 실패이기도 하다.
	 */
	public static PgApproveResult approvalWindowClosed(String message, PgCallRecord callRecord) {
		return new PgApproveResult(
			PgOutcome.TERMINAL_FAILURE, true, true, null, null, null, null, message, callRecord);
	}
}
