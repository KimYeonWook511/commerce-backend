package com.commerce.payment.application.port;

/**
 * 자동으로는 풀리지 않는 일을 사람에게 알린다.
 *
 * <p>회원 식별자를 받지 않는다. 결제 키가 어긋난 자리는 서로 다른 두 회원의 값이 동시에 손에 있는
 * 유일한 지점이라, 그 자리에서 회원을 담으면 누구의 것인지 단정할 수 없는 값을 남기게 된다.
 */
public interface NotificationPort {

	void notifyManualReviewRequired(Long orderId, String paymentKey, String reason);
}
