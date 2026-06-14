package com.commerce.order.infrastructure;

/**
 * 주문 멱등성 저장소(Redis) 가 일시적으로 사용 불가함을 표현하는 예외.
 * application 이 catch 해 DB unique 안전망 경로로 fallback 진행한다.
 */
public class OrderIdempotencyStoreUnavailableException extends RuntimeException {

	public OrderIdempotencyStoreUnavailableException(Throwable cause) {
		super(cause);
	}
}
