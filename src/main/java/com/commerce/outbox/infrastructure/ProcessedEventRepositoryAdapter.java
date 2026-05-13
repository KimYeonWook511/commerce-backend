package com.commerce.outbox.infrastructure;

import org.springframework.stereotype.Repository;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

	private final JpaProcessedEventRepository jpaProcessedEventRepository;

	@Override
	public ProcessedEvent save(ProcessedEvent processedEvent) {
		return jpaProcessedEventRepository.saveAndFlush(processedEvent);
	}
}
