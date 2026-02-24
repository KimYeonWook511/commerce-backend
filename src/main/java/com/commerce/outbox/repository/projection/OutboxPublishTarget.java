package com.commerce.outbox.repository.projection;

import java.time.LocalDateTime;

import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEventType;

public interface OutboxPublishTarget {

	Long getId();

	String getEventId();

	OutboxEventType getEventType();

	OutboxAggregateType getAggregateType();

	Long getAggregateId();

	String getPayload();

	int getAttemptCount();

	LocalDateTime getCreatedAt();
}
