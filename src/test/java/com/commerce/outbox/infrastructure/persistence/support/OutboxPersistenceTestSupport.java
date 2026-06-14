package com.commerce.outbox.infrastructure.persistence.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.infrastructure.persistence.JpaOutboxEventRepository;
import com.commerce.outbox.infrastructure.persistence.JpaProcessedEventRepository;

import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

@TestComponent
@RequiredArgsConstructor
public class OutboxPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaOutboxEventRepository outboxEventRepository;
	private final JpaProcessedEventRepository processedEventRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.OUTBOX;
	}

	@Override
	public void deleteAllInBatch() {
		processedEventRepository.deleteAllInBatch();
		outboxEventRepository.deleteAllInBatch();
	}

	public OutboxEvent save(OutboxEvent event) {
		return outboxEventRepository.save(event);
	}

	public Optional<OutboxEvent> findById(Long id) {
		return outboxEventRepository.findById(id);
	}

	public long count() {
		return outboxEventRepository.count();
	}
}
