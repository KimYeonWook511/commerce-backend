package com.commerce.outbox.domain.repository;

import com.commerce.outbox.domain.ProcessedEvent;

public interface ProcessedEventRepository {

	ProcessedEvent save(ProcessedEvent processedEvent);
}
