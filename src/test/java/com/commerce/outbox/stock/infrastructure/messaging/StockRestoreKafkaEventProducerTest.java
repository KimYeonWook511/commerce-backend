package com.commerce.outbox.stock.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;
import com.commerce.outbox.infrastructure.messaging.OutboxRelayMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StockRestoreKafkaEventProducerTest {

	@Mock
	private KafkaTemplate<String, String> kafkaTemplate;

	@Mock
	private ObjectMapper objectMapper;

	@InjectMocks
	private StockRestoreKafkaEventProducer stockRestoreKafkaEventProducer;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(stockRestoreKafkaEventProducer, "topic", "stock-restore-events");
		ReflectionTestUtils.setField(stockRestoreKafkaEventProducer, "sendTimeoutMillis", 5000L);
	}

	@DisplayName("relay 메시지를 직렬화하고 Kafka 토픽에 eventId를 키로 발행한다")
	@Test
	void publish_whenValidMessage_sendToKafkaWithEventIdAsKey() throws Exception {
		// given
		OutboxPublishTarget target = publishTarget("01ARZ3NDEKTSV4RRFFQ69G5FAV");
		given(objectMapper.writeValueAsString(any(OutboxRelayMessage.class)))
			.willReturn("{\"eventId\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"}");
		given(kafkaTemplate.send(eq("stock-restore-events"), eq("01ARZ3NDEKTSV4RRFFQ69G5FAV"), anyString()))
			.willReturn(CompletableFuture.completedFuture(null));

		// when
		stockRestoreKafkaEventProducer.publish(target);

		// then
		then(kafkaTemplate).should().send(
			eq("stock-restore-events"),
			eq("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
			eq("{\"eventId\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"}")
		);
	}

	@DisplayName("직렬화에 실패하면 IllegalStateException을 던진다")
	@Test
	void publish_whenSerializationFails_throwIllegalStateException() throws Exception {
		// given
		OutboxPublishTarget target = publishTarget("01ARZ3NDEKTSV4RRFFQ69G5FAV");
		given(objectMapper.writeValueAsString(any(OutboxRelayMessage.class)))
			.willThrow(new JsonProcessingException("serialize failed") {});

		// when & then
		assertThatThrownBy(() -> stockRestoreKafkaEventProducer.publish(target))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to serialize relay message");
	}

	@DisplayName("Kafka 발행이 타임아웃되면 IllegalStateException을 던진다")
	@Test
	void publish_whenKafkaSendTimesOut_throwIllegalStateException() throws Exception {
		// given
		OutboxPublishTarget target = publishTarget("01ARZ3NDEKTSV4RRFFQ69G5FAV");
		given(objectMapper.writeValueAsString(any(OutboxRelayMessage.class)))
			.willReturn("{\"eventId\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"}");
		CompletableFuture<SendResult<String, String>> timedOutFuture = new CompletableFuture<>();
		timedOutFuture.completeExceptionally(new TimeoutException("kafka timeout"));
		given(kafkaTemplate.send(anyString(), anyString(), anyString()))
			.willReturn(timedOutFuture);

		// when & then
		assertThatThrownBy(() -> stockRestoreKafkaEventProducer.publish(target))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to publish outbox event");
	}

	@DisplayName("Kafka 발행 중 예외가 발생하면 IllegalStateException을 던진다")
	@Test
	void publish_whenKafkaSendFails_throwIllegalStateException() throws Exception {
		// given
		OutboxPublishTarget target = publishTarget("01ARZ3NDEKTSV4RRFFQ69G5FAV");
		given(objectMapper.writeValueAsString(any(OutboxRelayMessage.class)))
			.willReturn("{\"eventId\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"}");
		CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
		failedFuture.completeExceptionally(new RuntimeException("broker unavailable"));
		given(kafkaTemplate.send(anyString(), anyString(), anyString()))
			.willReturn(failedFuture);

		// when & then
		assertThatThrownBy(() -> stockRestoreKafkaEventProducer.publish(target))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to publish outbox event");
	}

	private OutboxPublishTarget publishTarget(String eventId) {
		OutboxPublishTarget target = mock(OutboxPublishTarget.class);
		given(target.getEventId()).willReturn(eventId);
		given(target.getEventType()).willReturn(OutboxEventType.STOCK_RESTORE_REQUESTED);
		given(target.getAggregateType()).willReturn(OutboxAggregateType.ORDER);
		given(target.getAggregateId()).willReturn(1L);
		given(target.getPayload()).willReturn("{\"orderId\":1}");
		given(target.getCreatedAt()).willReturn(LocalDateTime.of(2000, 1, 1, 0, 0));
		return target;
	}
}
