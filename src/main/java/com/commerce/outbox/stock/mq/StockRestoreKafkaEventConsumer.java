package com.commerce.outbox.stock.mq;

import java.io.IOException;
import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.mq.OutboxRelayMessage;
import com.commerce.outbox.stock.service.StockRestoreOutboxConsumeService;
import com.commerce.outbox.stock.service.command.StockRestoreConsumeCommand;
import com.commerce.outbox.stock.service.payload.StockRestoreRequestedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockRestoreKafkaEventConsumer {

	private final ObjectMapper objectMapper;
	private final StockRestoreOutboxConsumeService stockRestoreOutboxConsumeService;

	@KafkaListener(
		topics = "${outbox.stock-restore.relay.topic:stock-restore-events}",
		groupId = "stock-restore-consumer"
	)
	public void consume(String message) {
		OutboxRelayMessage relayMessage = deserializeRelayMessage(message);
		if (relayMessage.getEventType() != OutboxEventType.STOCK_RESTORE_REQUESTED) {
			log.warn("Skip unsupported outbox event type. eventId={}, eventType={}",
				relayMessage.getEventId(), relayMessage.getEventType());
			return;
		}

		StockRestoreRequestedPayload payload = deserializePayload(relayMessage.getPayload());
		stockRestoreOutboxConsumeService.consume(toStockRestoreConsumeCommand(relayMessage, payload));
	}

	private StockRestoreConsumeCommand toStockRestoreConsumeCommand(OutboxRelayMessage relayMessage, StockRestoreRequestedPayload payload) {
		List<StockRestoreConsumeCommand.Item> items = payload.getItems().stream()
			.map(item -> StockRestoreConsumeCommand.Item.builder()
				.productId(item.getProductId())
				.quantity(item.getQuantity())
				.build())
			.toList();

		return StockRestoreConsumeCommand.builder()
			.eventId(relayMessage.getEventId())
			.items(items)
			.build();
	}

	private OutboxRelayMessage deserializeRelayMessage(String message) {
		try {
			return objectMapper.readValue(message, OutboxRelayMessage.class);
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to deserialize relay message", ex);
		}
	}

	private StockRestoreRequestedPayload deserializePayload(String payload) {
		try {
			return objectMapper.readValue(payload, StockRestoreRequestedPayload.class);
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to deserialize stock restore payload", ex);
		}
	}

}
