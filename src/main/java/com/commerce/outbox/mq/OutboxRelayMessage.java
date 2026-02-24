package com.commerce.outbox.mq;

import java.time.LocalDateTime;

import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEvent;
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
}
