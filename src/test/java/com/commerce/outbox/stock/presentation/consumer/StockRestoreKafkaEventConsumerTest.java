package com.commerce.outbox.stock.presentation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.common.kafka.exception.KafkaConsumeNonRetryableException;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.infrastructure.messaging.OutboxRelayMessage;
import com.commerce.outbox.stock.application.StockRestoreOutboxConsumeService;
import com.commerce.outbox.stock.application.command.StockRestoreConsumeCommand;
import com.commerce.outbox.stock.application.payload.StockRestoreRequestedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StockRestoreKafkaEventConsumerTest {

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private StockRestoreOutboxConsumeService stockRestoreOutboxConsumeService;

	@InjectMocks
	private StockRestoreKafkaEventConsumer stockRestoreKafkaEventConsumer;

	@DisplayName("재고 복구 relay 메시지를 소비하면 payload를 파싱해 소비 서비스에 위임한다")
	@Test
	void consume_whenValidRelayMessage_delegateToConsumeService() throws Exception {
		// given
		String relayJson = "{\"relay\":true}";
		String payloadJson = "{\"payload\":true}";
		OutboxRelayMessage relayMessage = OutboxRelayMessage.builder()
			.eventId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
			.eventType(OutboxEventType.STOCK_RESTORE_REQUESTED)
			.aggregateType(OutboxAggregateType.ORDER)
			.aggregateId(1L)
			.payload(payloadJson)
			.occurredAt(LocalDateTime.of(2000, 1, 1, 0, 0))
			.build();
		StockRestoreRequestedPayload payload = StockRestoreRequestedPayload.builder()
			.orderId(1L)
			.items(List.of(
				StockRestoreRequestedPayload.Item.builder().productId(10L).quantity(2).build(),
				StockRestoreRequestedPayload.Item.builder().productId(20L).quantity(3).build()
			))
			.build();
		given(objectMapper.readValue(relayJson, OutboxRelayMessage.class)).willReturn(relayMessage);
		given(objectMapper.readValue(payloadJson, StockRestoreRequestedPayload.class)).willReturn(payload);

		// when
		stockRestoreKafkaEventConsumer.consume(relayJson);

		// then
		ArgumentCaptor<StockRestoreConsumeCommand> captor = ArgumentCaptor.forClass(StockRestoreConsumeCommand.class);
		then(stockRestoreOutboxConsumeService).should().consume(captor.capture());
		StockRestoreConsumeCommand command = captor.getValue();
		assertThat(command.getEventId()).isEqualTo("01ARZ3NDEKTSV4RRFFQ69G5FAV");
		assertThat(command.getItems()).hasSize(2);
		assertThat(command.getItems().getFirst().getProductId()).isEqualTo(10L);
		assertThat(command.getItems().getFirst().getQuantity()).isEqualTo(2);
		assertThat(command.getItems().get(1).getProductId()).isEqualTo(20L);
		assertThat(command.getItems().get(1).getQuantity()).isEqualTo(3);
	}

	@DisplayName("relay 메시지 역직렬화에 실패하면 예외를 던지고 소비 서비스를 호출하지 않는다")
	@Test
	void consume_whenRelayMessageDeserializationFails_throwException() throws Exception {
		// given
		String relayJson = "{\"invalid\":true}";
		given(objectMapper.readValue(eq(relayJson), eq(OutboxRelayMessage.class)))
			.willThrow(new JsonProcessingException("invalid relay") {
			});

		// when & then
		assertThatThrownBy(() -> stockRestoreKafkaEventConsumer.consume(relayJson))
			.isInstanceOf(KafkaConsumeNonRetryableException.class);
		then(stockRestoreOutboxConsumeService).should(never()).consume(org.mockito.ArgumentMatchers.any());
	}

	@DisplayName("재고 복구 payload 역직렬화에 실패하면 예외를 던지고 소비 서비스를 호출하지 않는다")
	@Test
	void consume_whenPayloadDeserializationFails_throwException() throws Exception {
		// given
		String relayJson = "{\"relay\":true}";
		String payloadJson = "{\"invalid\":true}";
		OutboxRelayMessage relayMessage = OutboxRelayMessage.builder()
			.eventId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
			.eventType(OutboxEventType.STOCK_RESTORE_REQUESTED)
			.aggregateType(OutboxAggregateType.ORDER)
			.aggregateId(1L)
			.payload(payloadJson)
			.occurredAt(LocalDateTime.of(2000, 1, 1, 0, 0))
			.build();
		given(objectMapper.readValue(relayJson, OutboxRelayMessage.class)).willReturn(relayMessage);
		given(objectMapper.readValue(payloadJson, StockRestoreRequestedPayload.class))
			.willThrow(new JsonProcessingException("invalid payload") {
			});

		// when & then
		assertThatThrownBy(() -> stockRestoreKafkaEventConsumer.consume(relayJson))
			.isInstanceOf(KafkaConsumeNonRetryableException.class);
		then(stockRestoreOutboxConsumeService).should(never()).consume(org.mockito.ArgumentMatchers.any());
	}

	@DisplayName("payload items가 비어있으면 비재시도 예외를 던지고 소비 서비스를 호출하지 않는다")
	@Test
	void consume_whenPayloadItemsIsEmpty_throwNonRetryableException() throws Exception {
		// given
		String relayJson = "{\"relay\":true}";
		String payloadJson = "{\"payload\":true}";
		OutboxRelayMessage relayMessage = OutboxRelayMessage.builder()
			.eventId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
			.eventType(OutboxEventType.STOCK_RESTORE_REQUESTED)
			.aggregateType(OutboxAggregateType.ORDER)
			.aggregateId(1L)
			.payload(payloadJson)
			.occurredAt(LocalDateTime.of(2000, 1, 1, 0, 0))
			.build();
		StockRestoreRequestedPayload payload = StockRestoreRequestedPayload.builder()
			.orderId(1L)
			.items(List.of())
			.build();
		given(objectMapper.readValue(relayJson, OutboxRelayMessage.class)).willReturn(relayMessage);
		given(objectMapper.readValue(payloadJson, StockRestoreRequestedPayload.class)).willReturn(payload);

		// when & then
		assertThatThrownBy(() -> stockRestoreKafkaEventConsumer.consume(relayJson))
			.isInstanceOf(KafkaConsumeNonRetryableException.class);
		then(stockRestoreOutboxConsumeService).should(never()).consume(org.mockito.ArgumentMatchers.any());
	}
}
