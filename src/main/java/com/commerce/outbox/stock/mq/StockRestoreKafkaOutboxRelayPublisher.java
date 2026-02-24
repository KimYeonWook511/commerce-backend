package com.commerce.outbox.stock.mq;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.commerce.outbox.mq.OutboxRelayMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StockRestoreKafkaOutboxRelayPublisher {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;

	@Value("${outbox.stock-restore.relay.topic:stock-restore-events}")
	private String topic;

	@Value("${outbox.stock-restore.relay.send-timeout-millis:5000}")
	private long sendTimeoutMillis;

	public void publish(OutboxRelayMessage relayMessage) {
		String message = serialize(relayMessage);
		try {
			kafkaTemplate.send(topic, relayMessage.getEventId(), message)
				.get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to publish outbox event", ex);
		}
	}

	private String serialize(OutboxRelayMessage message) {
		try {
			return objectMapper.writeValueAsString(message);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize relay message", ex);
		}
	}
}
