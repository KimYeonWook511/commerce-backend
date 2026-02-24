package com.commerce.outbox.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.ProcessedEventConsumerType;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

	boolean existsByEventIdAndConsumerType(String eventId, ProcessedEventConsumerType consumerType);

}
