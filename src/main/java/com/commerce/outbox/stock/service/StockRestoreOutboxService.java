package com.commerce.outbox.stock.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.mq.OutboxRelayMessage;
import com.commerce.outbox.repository.OutboxEventRepository;
import com.commerce.outbox.repository.projection.OutboxPublishTarget;
import com.commerce.outbox.stock.mq.StockRestoreKafkaOutboxRelayPublisher;
import com.commerce.outbox.stock.service.command.StockRestoreOutboxCreateCommand;
import com.commerce.outbox.stock.service.payload.StockRestoreRequestedPayload;
import com.commerce.outbox.stock.service.result.OutboxPublishResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockRestoreOutboxService {

	private static final int MAX_ERROR_LENGTH = 1000;
	private static final OutboxEventType STOCK_RESTORE_EVENT_TYPE = OutboxEventType.STOCK_RESTORE_REQUESTED;
	private final OutboxEventRepository outboxEventRepository;
	private final StockRestoreKafkaOutboxRelayPublisher outboxRelayPublisher;
	private final ObjectMapper objectMapper;

	@Value("${outbox.stock-restore.publisher.batch-size:100}")
	private int batchSize;

	@Value("${outbox.stock-restore.retry.base-seconds:30}")
	private long retryBaseSeconds;

	@Value("${outbox.stock-restore.retry.max-seconds:3600}")
	private long retryMaxSeconds;

	@Value("${outbox.stock-restore.stale-publishing-seconds:300}")
	private long stalePublishingSeconds;

	@Transactional
	public void createOutboxEvent(StockRestoreOutboxCreateCommand command) {
		String payload = serializePayload(StockRestoreRequestedPayload.from(command));
		OutboxEvent outboxEvent = OutboxEvent.createPending(
			UlidGenerator.generate(),
			STOCK_RESTORE_EVENT_TYPE,
			payload,
			command.getRequestedAt(),
			OutboxAggregateType.ORDER,
			command.getOrderId()
		);
		outboxEventRepository.save(outboxEvent);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public OutboxPublishResult publishPendingEvents(LocalDateTime now) {
		List<OutboxPublishTarget> targets = outboxEventRepository.findPendingPublishTargetProjections(
			STOCK_RESTORE_EVENT_TYPE,
			PageRequest.of(0, batchSize)
		);

		int successCount = 0;
		int failedCount = 0;
		int skippedCount = 0;
		for (OutboxPublishTarget target : targets) {
			if (!markPublishingFromPending(target)) {
				skippedCount += 1;
				continue;
			}

			PublishResult result = publishTarget(target, now);
			switch (result) {
				case PUBLISHED -> successCount += 1;
				case FAILED -> failedCount += 1;
				case SKIPPED -> skippedCount += 1;
			}
		}
		return toPublishResult(targets.size(), successCount, failedCount, skippedCount);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public OutboxPublishResult publishRetryableFailedEvents(LocalDateTime now) {
		List<OutboxPublishTarget> targets = outboxEventRepository.findRetryableFailedPublishTargetProjections(
			STOCK_RESTORE_EVENT_TYPE,
			now,
			PageRequest.of(0, batchSize)
		);

		int successCount = 0;
		int failedCount = 0;
		int skippedCount = 0;
		for (OutboxPublishTarget target : targets) {
			if (!markPublishingFromRetryableFailed(target, now)) {
				skippedCount += 1;
				continue;
			}

			PublishResult result = publishTarget(target, now);
			switch (result) {
				case PUBLISHED -> successCount += 1;
				case FAILED -> failedCount += 1;
				case SKIPPED -> skippedCount += 1;
			}
		}
		return toPublishResult(targets.size(), successCount, failedCount, skippedCount);
	}

	@Transactional
	public int recoverStalePublishingEvents(LocalDateTime now) {
		LocalDateTime staleThreshold = now.minusSeconds(stalePublishingSeconds);
		List<Long> staleTargetIds = outboxEventRepository.findStalePublishingTargetIds(
			STOCK_RESTORE_EVENT_TYPE,
			staleThreshold,
			PageRequest.of(0, batchSize)
		);
		if (staleTargetIds.isEmpty()) {
			return 0;
		}

		LocalDateTime nextRetryAt = now.plusSeconds(retryBaseSeconds);
		return outboxEventRepository.recoverStalePublishingEventsByIds(
			STOCK_RESTORE_EVENT_TYPE,
			staleTargetIds,
			nextRetryAt,
			"stale publishing timeout"
		);
	}

	private String serializePayload(StockRestoreRequestedPayload payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize outbox payload", ex);
		}
	}

	private PublishResult publishTarget(OutboxPublishTarget target, LocalDateTime now) {
		try {
			outboxRelayPublisher.publish(toRelayMessage(target));
			if (!markSent(target)) {
				return PublishResult.SKIPPED;
			}
			return PublishResult.PUBLISHED;
		} catch (RuntimeException ex) {
			handlePublishFailure(target, now, ex);
			return PublishResult.FAILED;
		}
	}

	private void handlePublishFailure(OutboxPublishTarget target, LocalDateTime now, RuntimeException ex) {
		int nextAttemptCount = target.getAttemptCount() + 1;
		long delaySeconds = calculateRetryDelaySeconds(nextAttemptCount);
		LocalDateTime nextRetryAt = now.plusSeconds(delaySeconds);
		try {
			outboxEventRepository.markFailed(
				target.getId(),
				STOCK_RESTORE_EVENT_TYPE,
				compactErrorMessage(ex),
				nextRetryAt
			);
			log.warn(
				"Outbox publish failed. outboxId={}, eventId={}, eventType={}, nextAttemptCount={}, delaySeconds={}, nextRetryAt={}",
				target.getId(),
				target.getEventId(),
				target.getEventType(),
				nextAttemptCount,
				delaySeconds,
				nextRetryAt,
				ex
			);
		} catch (RuntimeException markFailedEx) {
			log.error(
				"Outbox failure handling failed. outboxId={}, eventId={}, eventType={}, nextAttemptCount={}, nextRetryAt={}",
				target.getId(),
				target.getEventId(),
				target.getEventType(),
				nextAttemptCount,
				nextRetryAt,
				markFailedEx
			);
		}
	}

	private long calculateRetryDelaySeconds(int nextAttemptCount) {
		int shift = Math.min(nextAttemptCount - 1, 30);
		long delay = retryBaseSeconds * (1L << shift);
		if (delay < 0) {
			return retryMaxSeconds;
		}
		return Math.min(delay, retryMaxSeconds);
	}

	private String compactErrorMessage(RuntimeException ex) {
		String message = ex.getMessage();
		if (message == null || message.isBlank()) {
			message = ex.getClass().getSimpleName();
		}
		return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
	}

	private boolean markPublishingFromPending(OutboxPublishTarget target) {
		return outboxEventRepository.markPublishingFromPending(target.getId(), STOCK_RESTORE_EVENT_TYPE) > 0;
	}

	private boolean markPublishingFromRetryableFailed(OutboxPublishTarget target, LocalDateTime now) {
		return outboxEventRepository.markPublishingFromRetryableFailed(
			target.getId(),
			STOCK_RESTORE_EVENT_TYPE,
			now
		) > 0;
	}

	private boolean markSent(OutboxPublishTarget target) {
		return outboxEventRepository.markSent(target.getId(), STOCK_RESTORE_EVENT_TYPE) > 0;
	}

	private OutboxRelayMessage toRelayMessage(OutboxPublishTarget target) {
		return OutboxRelayMessage.builder()
			.eventId(target.getEventId())
			.eventType(target.getEventType())
			.aggregateType(target.getAggregateType())
			.aggregateId(target.getAggregateId())
			.payload(target.getPayload())
			.occurredAt(target.getCreatedAt())
			.build();
	}

	private OutboxPublishResult toPublishResult(
		int selectedCount,
		int publishedCount,
		int failedCount,
		int skippedCount
	) {
		return OutboxPublishResult.builder()
			.selectedCount(selectedCount)
			.publishedCount(publishedCount)
			.failedCount(failedCount)
			.skippedCount(skippedCount)
			.build();
	}

	private enum PublishResult {
		PUBLISHED,
		FAILED,
		SKIPPED
	}
}
