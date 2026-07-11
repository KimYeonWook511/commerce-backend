package com.commerce.outbox.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "tbl_processed_event",
	indexes = {
		@Index(name = "idx_processed_event_event_id_consumer_type", columnList = "eventId,consumerType", unique = true),
		// @Index(name = "idx_processed_event_created_at", columnList = "createdAt") // 이거 우선 보류!! 정말 쓰이는 지 확인후 인덱스로 추가하자
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class ProcessedEvent extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 26)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private ProcessedEventConsumerType consumerType;

	private ProcessedEvent(String eventId, ProcessedEventConsumerType consumerType) {
		this.eventId = eventId;
		this.consumerType = consumerType;
	}

	public static ProcessedEvent create(String eventId, ProcessedEventConsumerType consumerType) {
		return new ProcessedEvent(eventId, consumerType);
	}

}
