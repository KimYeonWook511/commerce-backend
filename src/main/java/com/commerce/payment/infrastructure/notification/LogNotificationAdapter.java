package com.commerce.payment.infrastructure.notification;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.NotificationPort;

import lombok.extern.slf4j.Slf4j;

/**
 * 지금 통지 수단은 서버 로그 한 줄이다. 받아서 처리하는 경로가 정해지면 이 자리를 바꾼다.
 */
@Slf4j
@Component
public class LogNotificationAdapter implements NotificationPort {

	@Override
	public void notifyManualReviewRequired(Long orderId, String paymentKey, String reason) {
		log.error("수동 검토 필요 orderId={} paymentKey={} reason={}", orderId, paymentKey, reason);
	}
}
