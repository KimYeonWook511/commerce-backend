package com.commerce.payment.infrastructure.notification;

import com.commerce.payment.application.port.NotificationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogNotificationAdapter implements NotificationPort {

	@Override
	public void notifyManualReviewRequired(Long orderId, String merchantPayKey, String reason) {
		log.error("수동 검토 필요 orderId={} merchantPayKey={} reason={}", orderId, merchantPayKey, reason);
	}
}
