package com.commerce.outbox.stock.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.log.LogContext;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;
import com.commerce.outbox.domain.repository.OutboxEventRepository;
import com.commerce.outbox.stock.application.port.StockRestoreEventPublisher;
import com.commerce.outbox.stock.application.result.OutboxPublishResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockRestoreOutboxRelayService {

	private static final int MAX_ERROR_LENGTH = 1000;
	private static final OutboxEventType STOCK_RESTORE_EVENT_TYPE = OutboxEventType.STOCK_RESTORE_REQUESTED;

	private final OutboxEventRepository outboxEventRepository;
	private final StockRestoreEventPublisher eventPublisher;

	@Value("${outbox.stock-restore.producer.batch-size:100}")
	private int batchSize;

	@Value("${outbox.stock-restore.retry.base-seconds:30}")
	private long retryBaseSeconds;

	@Value("${outbox.stock-restore.retry.max-seconds:3600}")
	private long retryMaxSeconds;

	@Value("${outbox.stock-restore.stale-publishing-seconds:300}")
	private long stalePublishingSeconds;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public OutboxPublishResult publishPendingEvents(LocalDateTime now) {
		List<OutboxPublishTarget> targets = outboxEventRepository.findPendingPublishTargets(
			STOCK_RESTORE_EVENT_TYPE,
			batchSize
		);

		int publishedCount = 0;
		int failedCount = 0;
		int skippedCount = 0;
		for (OutboxPublishTarget target : targets) {
			if (!markPublishingFromPending(target)) {
				skippedCount += 1;
				continue;
			}

			PublishResult result = publishTarget(target, now);
			switch (result) {
				case PUBLISHED -> publishedCount += 1;
				case FAILED -> failedCount += 1;
				case SKIPPED -> skippedCount += 1;
			}
		}
		return toPublishResult(targets.size(), publishedCount, failedCount, skippedCount);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public OutboxPublishResult publishRetryableFailedEvents(LocalDateTime now) {
		List<OutboxPublishTarget> targets = outboxEventRepository.findRetryableFailedPublishTargets(
			STOCK_RESTORE_EVENT_TYPE,
			now,
			batchSize
		);

		int publishedCount = 0;
		int failedCount = 0;
		int skippedCount = 0;
		for (OutboxPublishTarget target : targets) {
			if (!markPublishingFromRetryableFailed(target, now)) {
				skippedCount += 1;
				continue;
			}

			PublishResult result = publishTarget(target, now);
			switch (result) {
				case PUBLISHED -> publishedCount += 1;
				case FAILED -> failedCount += 1;
				case SKIPPED -> skippedCount += 1;
			}
		}
		return toPublishResult(targets.size(), publishedCount, failedCount, skippedCount);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public int recoverStalePublishingEvents(LocalDateTime now) {
		LocalDateTime staleThreshold = now.minusSeconds(stalePublishingSeconds);
		List<Long> staleTargetIds = outboxEventRepository.findStalePublishingTargetIds(
			STOCK_RESTORE_EVENT_TYPE,
			staleThreshold,
			batchSize
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

	private PublishResult publishTarget(OutboxPublishTarget target, LocalDateTime now) {
		boolean traceIdPushed = pushTraceIdIfMissing(target.getTraceId());
		try {
			try {
				eventPublisher.publish(target);
				if (!markSent(target)) {
					return PublishResult.SKIPPED;
				}
				return PublishResult.PUBLISHED;
			} catch (RuntimeException ex) {
				handlePublishFailure(target, now, ex);
				return PublishResult.FAILED;
			}
		} finally {
			if (traceIdPushed) {
				LogContext.removeTraceId();
			}
		}
	}

	// 스케줄러 호출 시점에는 MDC가 비어 있어 target의 traceId를 push한다.
	// 향후 HTTP 흐름에서 직접 호출되어 MDC에 traceId가 이미 있는 경우에는 그대로 보존한다.
	private boolean pushTraceIdIfMissing(String targetTraceId) {
		if (LogContext.isValidTraceId(LogContext.getTraceId())) {
			return false;
		}
		if (LogContext.isValidTraceId(targetTraceId)) {
			LogContext.putTraceId(targetTraceId);
			return true;
		}
		return false;
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
