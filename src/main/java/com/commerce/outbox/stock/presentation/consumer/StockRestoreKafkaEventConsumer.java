package com.commerce.outbox.stock.presentation.consumer;

import java.io.IOException;
import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.commerce.common.kafka.exception.KafkaConsumeNonRetryableException;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.infrastructure.messaging.OutboxRelayMessage;
import com.commerce.outbox.stock.application.service.StockRestoreOutboxConsumeService;
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
		// relay 메시지 역직렬화 및 필수 필드 검증 (실패 시 비재시도 예외)
		OutboxRelayMessage relayMessage = deserializeRelayMessage(message);
		validateRelayMessage(relayMessage);

		log.info("Consume stock restore relay message. eventId={}, eventType={}",
			relayMessage.getEventId(), relayMessage.getEventType());

		// 현재 컨슈머가 담당하지 않는 이벤트 타입은 skip 처리
		if (relayMessage.getEventType() != OutboxEventType.STOCK_RESTORE_REQUESTED) {
			log.warn("Skip unsupported outbox event type. eventId={}, eventType={}",
				relayMessage.getEventId(), relayMessage.getEventType());
			return;
		}

		// payload 역직렬화 및 DTO 유효성 검증
		StockRestoreRequestedPayload payload = deserializePayload(relayMessage.getPayload());
		validatePayload(payload);

		// command로 변환 후 소비 서비스에 위임 (실패 시 Kafka 재시도 대상)
		int itemCount = payload.getItems().size();
		stockRestoreOutboxConsumeService.consume(toStockRestoreConsumeCommand(relayMessage, payload));

		// 정상 소비 완료 로그
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
			// 메시지 형식 자체가 잘못된 경우는 재시도해도 성공 가능성이 낮음 (비재시도 예외)
			log.warn("Failed to deserialize relay message. messageLength={}",
				message == null ? 0 : message.length(), ex);
			throw new KafkaConsumeNonRetryableException("Failed to deserialize relay message", ex);
		}
	}

	private StockRestoreRequestedPayload deserializePayload(String payload) {
		try {
			return objectMapper.readValue(payload, StockRestoreRequestedPayload.class);
		} catch (IOException ex) {
			// payload 형식 오류는 재시도해도 성공 가능성이 낮음 (비재시도 예외)
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
