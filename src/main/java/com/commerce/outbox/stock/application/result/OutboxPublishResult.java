package com.commerce.outbox.stock.application.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OutboxPublishResult {

	private final int selectedCount;
	private final int publishedCount;
	private final int failedCount;
	private final int skippedCount;

	@Builder
	private OutboxPublishResult(
		int selectedCount,
		int publishedCount,
		int failedCount,
		int skippedCount
	) {
		this.selectedCount = selectedCount;
		this.publishedCount = publishedCount;
		this.failedCount = failedCount;
		this.skippedCount = skippedCount;
	}
}
