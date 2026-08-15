package com.commerce.payment.domain.exception;

/**
 * 같은 요청 키의 환불을 다른 쪽이 먼저 만들었다.
 *
 * <p>유일 제약 위반 중 이것만 골라내려면 타입이 필요하다. 무결성 위반은 모두 같은 기술 예외로 오는데,
 * 이 경우만 회원에게 "잠시 후 다시"라고 답할 수 있고 나머지(필수값 누락·외래 키)는 코드 결함이라
 * 안전망으로 보내야 하기 때문이다. 번역은 제약 이름을 볼 수 있는 persistence adapter가 한다.
 */
public class DuplicateRefundRequestException extends PaymentException {

	public DuplicateRefundRequestException() {
		super(PaymentErrorCode.REFUND_REQUEST_IN_PROGRESS);
	}
}
