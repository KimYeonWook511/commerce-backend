package com.commerce.outbox.stock.presentation.scheduler;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.commerce.outbox.stock.application.usecase.StockRestoreOutboxRelayUseCase;
import com.commerce.outbox.stock.application.dto.OutboxPublishResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class StockRestoreOutboxScheduler {

	private final StockRestoreOutboxRelayUseCase stockRestoreOutboxRelayService;

	@Scheduled(cron = "${outbox.stock-restore.producer.cron:*/10 * * * * *}")
	public void publishPendingEvents() {
		LocalDateTime now = LocalDateTime.now();
		OutboxPublishResult publishResult = stockRestoreOutboxRelayService.publishPendingEvents(now);
		logPublishResult("outbox-stock-restore-pending-publish", publishResult);
	}

	@Scheduled(cron = "${outbox.stock-restore.retry-producer.cron:*/10 * * * * *}")
	public void publishRetryableFailedEvents() {
		LocalDateTime now = LocalDateTime.now();
		OutboxPublishResult publishResult = stockRestoreOutboxRelayService.publishRetryableFailedEvents(now);
		logPublishResult("outbox-stock-restore-retry-publish", publishResult);
	}

	@Scheduled(cron = "${outbox.stock-restore.stale-recovery.cron:*/30 * * * * *}")
	public void recoverStalePublishingEvents() {
		LocalDateTime now = LocalDateTime.now();
		int recoveredCount = stockRestoreOutboxRelayService.recoverStalePublishingEvents(now);
		if (recoveredCount > 0) {
			log.warn("job=outbox-stock-restore-stale-recovery recovered={}", recoveredCount);
		}
	}

	private void logPublishResult(String jobName, OutboxPublishResult result) {
		if (result.getSelectedCount() == 0
			&& result.getPublishedCount() == 0
			&& result.getFailedCount() == 0
			&& result.getSkippedCount() == 0) {
			return;
		}
		log.info(
			"job={} selected={} published={} failed={} skipped={}",
			jobName,
			result.getSelectedCount(),
			result.getPublishedCount(),
			result.getFailedCount(),
			result.getSkippedCount()
		);
	}

}
