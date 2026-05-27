package com.commerce.outbox.infrastructure;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;

public interface JpaOutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	@Query("""
		select
			e.id as id,
			e.eventId as eventId,
			e.eventType as eventType,
			e.aggregateType as aggregateType,
			e.aggregateId as aggregateId,
			e.payload as payload,
			e.attemptCount as attemptCount,
			e.createdAt as createdAt,
			e.traceId as traceId
		from OutboxEvent e
		where e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.PENDING
		order by e.id asc
		""")
	List<OutboxPublishTarget> findPendingPublishTargets(
		@Param("eventType") OutboxEventType eventType,
		Pageable pageable
	);

	@Query("""
		select
			e.id as id,
			e.eventId as eventId,
			e.eventType as eventType,
			e.aggregateType as aggregateType,
			e.aggregateId as aggregateId,
			e.payload as payload,
			e.attemptCount as attemptCount,
			e.createdAt as createdAt,
			e.traceId as traceId
		from OutboxEvent e
		where e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.FAILED
		and e.nextRetryAt <= :now
		order by e.id asc
		""")
	List<OutboxPublishTarget> findRetryableFailedPublishTargets(
		@Param("eventType") OutboxEventType eventType,
		@Param("now") LocalDateTime now,
		Pageable pageable
	);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update OutboxEvent e
		set e.status = com.commerce.outbox.domain.OutboxEventStatus.PUBLISHING,
			e.lastError = null
		where e.id = :outboxId
		and e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.PENDING
		""")
	int markPublishingFromPending(
		@Param("outboxId") Long outboxId,
		@Param("eventType") OutboxEventType eventType
	);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update OutboxEvent e
		set e.status = com.commerce.outbox.domain.OutboxEventStatus.PUBLISHING,
			e.lastError = null
		where e.id = :outboxId
		and e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.FAILED
		and e.nextRetryAt <= :now
		""")
	int markPublishingFromRetryableFailed(
		@Param("outboxId") Long outboxId,
		@Param("eventType") OutboxEventType eventType,
		@Param("now") LocalDateTime now
	);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update OutboxEvent e
		set e.status = com.commerce.outbox.domain.OutboxEventStatus.SENT,
			e.publishedAt = CURRENT_TIMESTAMP,
			e.lastError = null
		where e.id = :outboxId
		and e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.PUBLISHING
		""")
	int markSent(
		@Param("outboxId") Long outboxId,
		@Param("eventType") OutboxEventType eventType
	);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update OutboxEvent e
		set e.status = com.commerce.outbox.domain.OutboxEventStatus.FAILED,
			e.attemptCount = e.attemptCount + 1,
			e.lastError = :lastError,
			e.nextRetryAt = :nextRetryAt
		where e.id = :outboxId
		and e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.PUBLISHING
		""")
	int markFailed(
		@Param("outboxId") Long outboxId,
		@Param("eventType") OutboxEventType eventType,
		@Param("lastError") String lastError,
		@Param("nextRetryAt") LocalDateTime nextRetryAt
	);

	@Query("""
		select e.id
		from OutboxEvent e
		where e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.PUBLISHING
		and e.updatedAt < :staleThreshold
		order by e.id asc
		""")
	List<Long> findStalePublishingTargetIds(
		@Param("eventType") OutboxEventType eventType,
		@Param("staleThreshold") LocalDateTime staleThreshold,
		Pageable pageable
	);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update OutboxEvent e
		set e.status = com.commerce.outbox.domain.OutboxEventStatus.FAILED,
			e.attemptCount = e.attemptCount + 1,
			e.lastError = :lastError,
			e.nextRetryAt = :nextRetryAt
		where e.eventType = :eventType
		and e.status = com.commerce.outbox.domain.OutboxEventStatus.PUBLISHING
		and e.id in :targetIds
		""")
	int recoverStalePublishingEventsByIds(
		@Param("eventType") OutboxEventType eventType,
		@Param("targetIds") List<Long> targetIds,
		@Param("nextRetryAt") LocalDateTime nextRetryAt,
		@Param("lastError") String lastError
	);
}
