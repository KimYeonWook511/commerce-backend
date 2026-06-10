package com.commerce.payment.application.port;

public interface NotificationPort {

	void notifyManualReviewRequired(Long orderId, String merchantPayKey, String reason);
}
