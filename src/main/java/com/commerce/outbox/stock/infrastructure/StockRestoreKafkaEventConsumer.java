package com.commerce.outbox.stock.infrastructure;

import java.io.IOException;
import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.commerce.common.kafka.exception.KafkaConsumeNonRetryableException;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.infrastructure.OutboxRelayMessage;
import com.commerce.outbox.stock.application.StockRestoreOutboxConsumeService;
import com.commerce.outbox.stock.application.command.StockRestoreConsumeCommand;
import com.commerce.outbox.stock.application.payload.StockRestoreRequestedPayload;
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
		groupId = "${outbox.stock-restore.consumer.group-id:stock-restore-consumer}",
		containerFactory = "stockRestoreKafkaListenerContainerFactory"
	)
	public void consume(String message) {
		OutboxRelayMessage relayMessage = deserializeRelayMessage(message);
		validateRelayMessage(relayMessage);

		log.info("Consume stock restore relay message. eventId={}, eventType={}",
			relayMessage.getEventId(), relayMessage.getEventType());

		if (relayMessage.getEventType() != OutboxEventType.STOCK_RESTORE_REQUESTED) {
			log.warn("Skip unsupported outbox event type. eventId={}, eventType={}",
				relayMessage.getEventId(), relayMessage.getEventType());
			return;
		}

		StockRestoreRequestedPayload payload = deserializePayload(relayMessage.getPayload());
		validatePayload(payload);

		int itemCount = payload.getItems().size();
		stockRestoreOutboxConsumeService.consume(toStockRestoreConsumeCommand(relayMessage, payload));

		log.info("Consumed stock restore relay message. eventId={}, eventType={}, itemCount={}",
			relayMessage.getEventId(), relayMessage.getEventType(), itemCount);
	}

	private StockRestoreConsumeCommand toStockRestoreConsumeCommand(OutboxRelayMessage relayMessage,
		StockRestoreRequestedPayload payload) {
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
			log.warn("Failed to deserialize relay message. messageLength={}",
				message == null ? 0 : message.length(), ex);
			throw new KafkaConsumeNonRetryableException("Failed to deserialize relay message", ex);
		}
	}

	private StockRestoreRequestedPayload deserializePayload(String payload) {
		try {
			return objectMapper.readValue(payload, StockRestoreRequestedPayload.class);
		} catch (IOException ex) {
			log.warn("Failed to deserialize stock restore payload. payloadLength={}", payload.length(), ex);
			throw new KafkaConsumeNonRetryableException("Failed to deserialize stock restore payload", ex);
		}
	}

	private void validateRelayMessage(OutboxRelayMessage relayMessage) {
		if (relayMessage == null || !relayMessage.hasRequiredFields()) {
			throw new KafkaConsumeNonRetryableException("Invalid outbox relay message");
		}
	}

	private void validatePayload(StockRestoreRequestedPayload payload) {
		if (payload == null || !payload.hasValidItems()) {
			throw new KafkaConsumeNonRetryableException("Invalid stock restore payload");
		}
	}
}
