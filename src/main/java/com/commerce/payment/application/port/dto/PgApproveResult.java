package com.commerce.payment.application.port.dto;

/**
 * 승인 호출의 결과. 결제사 어휘가 없다 — 어댑터가 접은 갈래와 우리 값으로 옮긴 항목만 담는다.
 *
 * <p>승인이 성립한 경우에만 아래 셋이 채워진다. 그 밖의 갈래에서는 결제사가 값을 주지 않으므로
 * 비어 있고, 무엇을 할지는 갈래가 정한다.
 *
 * @param outcome        네 갈래 중 하나
 * @param paymentKey     응답에 실려 온 우리 결제 키. 이 승인이 그 결제의 것인지 대조하는 값이다
 * @param approvedAmount 결제사가 승인한 금액. 한도 계산의 기준이 되는 값이다
 * @param pgTransactionId 결제사가 발급한 거래 번호. 판정에는 쓰지 않고 정산 대조·조사에 쓴다
 * @param message        결제사가 준 문구. 종결 상세에 남기며 분기에 쓰지 않는다
 * @param callRecord     호출 기록에 담을 값
 */
public record PgApproveResult(
	PgOutcome outcome,
	String paymentKey,
	Integer approvedAmount,
	String pgTransactionId,
	String message,
	PgCallRecord callRecord
) {

	public static PgApproveResult succeeded(
		String paymentKey,
		int approvedAmount,
		String pgTransactionId,
		String message,
		PgCallRecord callRecord
	) {
		return new PgApproveResult(
			PgOutcome.SUCCEEDED, paymentKey, approvedAmount, pgTransactionId, message, callRecord);
	}

	public static PgApproveResult unknown(String message, PgCallRecord callRecord) {
		return new PgApproveResult(PgOutcome.UNKNOWN, null, null, null, message, callRecord);
	}

	public static PgApproveResult retryableFailure(String message, PgCallRecord callRecord) {
		return new PgApproveResult(PgOutcome.RETRYABLE_FAILURE, null, null, null, message, callRecord);
	}

	public static PgApproveResult terminalFailure(String message, PgCallRecord callRecord) {
		return new PgApproveResult(PgOutcome.TERMINAL_FAILURE, null, null, null, message, callRecord);
	}
}
