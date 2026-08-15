package com.commerce.payment.application.port.dto;

/**
 * 결제사를 부른 결과. 도메인이 아는 것은 이 넷뿐이고, 어댑터가 결제사 응답을 여기로 접어 넘긴다.
 *
 * <p>두 실패를 가르는 것이 이 목록의 핵심이다 — 하나는 다시 보내야 하고 다른 하나는 사람에게 넘겨야
 * 하는데, 실패가 하나면 무엇을 할지 정할 수 없다.
 */
public enum PgOutcome {

	/** 됐다 */
	SUCCEEDED,
	/** 모른다. 요청이 처리됐을 수 있어 실패로 단정하면 나간 돈을 안 나간 것으로 다루게 된다 */
	UNKNOWN,
	/** 다시 시도할 수 있는 실패. 점검·요청 제한·설정 문제처럼 시간이 지나거나 고치면 풀린다 */
	RETRYABLE_FAILURE,
	/** 다시 시도할 수 없는 실패. 같은 요청을 다시 보내도 같은 답이 온다 */
	TERMINAL_FAILURE
}
