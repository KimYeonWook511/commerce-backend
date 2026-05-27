package com.commerce.order.application.event;

import java.time.Duration;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderIdempotencyCacheEvent {

	private final Long memberId;
	private final String idempotencyKey;
	private final Long orderId;
	private final Duration ttl;
	private final String traceId;
}
