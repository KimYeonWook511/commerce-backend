package com.commerce.outbox.infrastructure;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;
import com.commerce.outbox.domain.repository.OutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryAdapter implements OutboxEventRepository {

	private final JpaOutboxEventRepository jpaOutboxEventRepository;

	@Override
	public OutboxEvent save(OutboxEvent outboxEvent) {
		return jpaOutboxEventRepository.save(outboxEvent);
	}

	@Override
	public List<OutboxPublishTarget> findPendingPublishTargets(OutboxEventType eventType, int limit) {
		return jpaOutboxEventRepository.findPendingPublishTargets(eventType, PageRequest.of(0, limit));
	}

	@Override
	public List<OutboxPublishTarget> findRetryableFailedPublishTargets(OutboxEventType eventType, LocalDateTime now,
		int limit) {
		return jpaOutboxEventRepository.findRetryableFailedPublishTargets(eventType, now, PageRequest.of(0, limit));
	}

	@Override
	public int markPublishingFromPending(Long outboxId, OutboxEventType eventType) {
		return jpaOutboxEventRepository.markPublishingFromPending(outboxId, eventType);
	}

	@Override
	public int markPublishingFromRetryableFailed(Long outboxId, OutboxEventType eventType, LocalDateTime now) {
		return jpaOutboxEventRepository.markPublishingFromRetryableFailed(outboxId, eventType, now);
	}

	@Override
	public int markSent(Long outboxId, OutboxEventType eventType) {
		return jpaOutboxEventRepository.markSent(outboxId, eventType);
	}

	@Override
	public int markFailed(Long outboxId, OutboxEventType eventType, String lastError, LocalDateTime nextRetryAt) {
		return jpaOutboxEventRepository.markFailed(outboxId, eventType, lastError, nextRetryAt);
	}

	@Override
	public List<Long> findStalePublishingTargetIds(OutboxEventType eventType, LocalDateTime staleThreshold, int limit) {
		return jpaOutboxEventRepository.findStalePublishingTargetIds(eventType, staleThreshold, PageRequest.of(0, limit));
	}

	@Override
	public int recoverStalePublishingEventsByIds(OutboxEventType eventType, List<Long> targetIds,
		LocalDateTime nextRetryAt, String lastError) {
		return jpaOutboxEventRepository.recoverStalePublishingEventsByIds(eventType, targetIds, nextRetryAt, lastError);
	}
}
