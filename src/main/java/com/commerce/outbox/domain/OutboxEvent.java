package com.commerce.outbox.domain;

import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "tbl_outbox_event",
	indexes = {
		@Index(name = "idx_outbox_event_type_status_next_retry_id", columnList = "eventType,status,nextRetryAt,id")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class OutboxEvent extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 26)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private OutboxEventType eventType;

	@Lob
	@Column(nullable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private OutboxEventStatus status;

	@Column(nullable = false)
	private int attemptCount;

	@Column(nullable = false)
	private LocalDateTime nextRetryAt;

	private LocalDateTime publishedAt;

	@Column(length = 1000)
	private String lastError;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private OutboxAggregateType aggregateType;

	@Column(nullable = false)
	private Long aggregateId;

	@Builder
	private OutboxEvent(
		String eventId,
		OutboxEventType eventType,
		String payload,
		OutboxEventStatus status,
		int attemptCount,
		LocalDateTime nextRetryAt,
		LocalDateTime publishedAt,
		String lastError,
		OutboxAggregateType aggregateType,
		Long aggregateId
	) {
		this.eventId = eventId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = status;
		this.attemptCount = attemptCount;
		this.nextRetryAt = nextRetryAt;
		this.publishedAt = publishedAt;
		this.lastError = lastError;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
	}

	public static OutboxEvent createPending(
		String eventId,
		OutboxEventType eventType,
		String payload,
		LocalDateTime nextRetryAt,
		OutboxAggregateType aggregateType,
		Long aggregateId
	) {
		return OutboxEvent.builder()
			.eventId(eventId)
			.eventType(eventType)
			.payload(payload)
			.status(OutboxEventStatus.PENDING)
			.attemptCount(0)
			.nextRetryAt(nextRetryAt)
			.aggregateType(aggregateType)
			.aggregateId(aggregateId)
			.build();
	}

}
