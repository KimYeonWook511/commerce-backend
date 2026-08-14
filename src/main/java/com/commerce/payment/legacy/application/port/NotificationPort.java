package com.commerce.payment.legacy.application.port;

public interface NotificationPort {

	void notifyManualReviewRequired(Long orderId, String merchantPayKey, String reason);
}
