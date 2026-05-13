package com.commerce.outbox.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;

public interface OutboxEventRepository {

	OutboxEvent save(OutboxEvent outboxEvent);

	List<OutboxPublishTarget> findPendingPublishTargets(OutboxEventType eventType, int limit);

	List<OutboxPublishTarget> findRetryableFailedPublishTargets(OutboxEventType eventType, LocalDateTime now, int limit);

	int markPublishingFromPending(Long outboxId, OutboxEventType eventType);

	int markPublishingFromRetryableFailed(Long outboxId, OutboxEventType eventType, LocalDateTime now);

	int markSent(Long outboxId, OutboxEventType eventType);

	int markFailed(Long outboxId, OutboxEventType eventType, String lastError, LocalDateTime nextRetryAt);

	List<Long> findStalePublishingTargetIds(OutboxEventType eventType, LocalDateTime staleThreshold, int limit);

	int recoverStalePublishingEventsByIds(OutboxEventType eventType, List<Long> targetIds, LocalDateTime nextRetryAt,
		String lastError);
}
