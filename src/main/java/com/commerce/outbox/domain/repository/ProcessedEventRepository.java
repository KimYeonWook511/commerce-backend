package com.commerce.outbox.domain.repository;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.ProcessedEventConsumerType;

public interface ProcessedEventRepository {

	ProcessedEvent save(ProcessedEvent processedEvent);

	boolean existsByEventIdAndConsumerType(String eventId, ProcessedEventConsumerType consumerType);
}
