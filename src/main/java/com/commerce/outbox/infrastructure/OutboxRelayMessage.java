package com.commerce.outbox.infrastructure;

import java.time.LocalDateTime;

import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEventType;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OutboxRelayMessage {

	private String eventId;
	private OutboxEventType eventType;
	private OutboxAggregateType aggregateType;
	private Long aggregateId;
	private String payload;
	private LocalDateTime occurredAt;

	@Builder
	private OutboxRelayMessage(
		String eventId,
		OutboxEventType eventType,
		OutboxAggregateType aggregateType,
		Long aggregateId,
		String payload,
		LocalDateTime occurredAt
	) {
		this.eventId = eventId;
		this.eventType = eventType;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.payload = payload;
		this.occurredAt = occurredAt;
	}

	public boolean hasRequiredFields() {
		return !isBlank(eventId)
			&& eventType != null
			&& !isBlank(payload);
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
