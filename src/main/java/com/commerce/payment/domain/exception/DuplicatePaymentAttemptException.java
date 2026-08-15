package com.commerce.payment.domain.exception;

/**
 * 결제 시작이 유일 제약에 막혔다. 활성 슬롯이든 회원 멱등키든 결제 키든 부딪혔다는 것은 같은 자리를
 * 다른 요청이 먼저 잡았다는 뜻이라 회원이 할 일이 같다.
 *
 * <p>무결성 위반 중 이것만 골라내려면 타입이 필요하다. 필수값 누락·외래 키 위반은 같은 기술 예외로
 * 오지만 회원이 할 수 있는 일이 없는 결함이라 안전망으로 보내야 한다. 번역은 제약 이름을 볼 수 있는
 * persistence adapter가 한다.
 */
public class DuplicatePaymentAttemptException extends PaymentException {

	public DuplicatePaymentAttemptException() {
		super(PaymentErrorCode.PAYMENT_REQUEST_IN_PROGRESS);
	}
}
