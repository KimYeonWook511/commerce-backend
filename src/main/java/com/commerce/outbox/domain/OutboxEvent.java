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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "tbl_outbox_event",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_outbox_event_event_id", columnNames = {"event_id"})
	},
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

	@Column(nullable = false, length = 26)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private OutboxEventType eventType;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
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

	@Column(length = 64)
	private String traceId;

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
		Long aggregateId,
		String traceId
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
		this.traceId = traceId;
	}

	public static OutboxEvent createPending(
		String eventId,
		OutboxEventType eventType,
		String payload,
		LocalDateTime nextRetryAt,
		OutboxAggregateType aggregateType,
		Long aggregateId,
		String traceId
	) {
		return new OutboxEvent(
			eventId,
			eventType,
			payload,
			OutboxEventStatus.PENDING,
			0,
			nextRetryAt,
			null,
			null,
			aggregateType,
			aggregateId,
			traceId
		);
	}

}
