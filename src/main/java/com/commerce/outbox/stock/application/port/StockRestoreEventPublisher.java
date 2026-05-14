package com.commerce.outbox.stock.application.port;

import com.commerce.outbox.domain.OutboxPublishTarget;

public interface StockRestoreEventPublisher {

	void publish(OutboxPublishTarget target);
}
