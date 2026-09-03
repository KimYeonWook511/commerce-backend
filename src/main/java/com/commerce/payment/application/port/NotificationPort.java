package com.commerce.payment.application.port;

/**
 * 자동으로는 풀리지 않는 일을 사람에게 알린다.
 *
 * <p>회원 식별자를 받지 않는다. 결제 키가 어긋난 자리는 서로 다른 두 회원의 값이 동시에 손에 있는
 * 유일한 지점이라, 그 자리에서 회원을 담으면 누구의 것인지 단정할 수 없는 값을 남기게 된다.
 */
public interface NotificationPort {

	void notifyManualReviewRequired(Long orderId, String paymentKey, String reason);

	/**
	 * 한 회차의 대사 대상이 너무 많다는 것을 알린다. 대상을 개수로 자르지 않으므로 밀렸다는 사실은 이
	 * 알림으로만 드러난다.
	 *
	 * @param subject     무엇이 밀렸나
	 * @param targetCount 이번 회차의 대상 수
	 * @param threshold   이 수를 넘으면 밀린 것으로 보기로 한 값
	 */
	void notifyReconcileBacklog(String subject, int targetCount, int threshold);
}
