package com.commerce.outbox.domain;

import java.time.LocalDateTime;

public interface OutboxPublishTarget {

	Long getId();

	String getEventId();

	OutboxEventType getEventType();

	OutboxAggregateType getAggregateType();

	Long getAggregateId();

	String getPayload();

	int getAttemptCount();

	LocalDateTime getCreatedAt();

	String getTraceId();
}
