package com.commerce.outbox.stock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.common.log.LogContext;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventStatus;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.repository.OutboxEventRepository;
import com.commerce.outbox.stock.application.command.StockRestoreOutboxCreateCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StockRestoreOutboxCreateServiceTest {

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private ObjectMapper objectMapper;

	@InjectMocks
	private StockRestoreOutboxCreateService stockRestoreOutboxCreateService;

	@AfterEach
	void cleanUpMdc() {
		LogContext.removeTraceId();
	}

	@DisplayName("재고 복구 outbox 이벤트 생성 시 PENDING 이벤트를 저장한다")
	@Test
	void createOutboxEvent_whenValidCommand_savePendingEvent() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		LocalDateTime requestedAt = now.plusHours(1);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(requestedAt)
			.items(List.of(
				StockRestoreOutboxCreateCommand.Item.builder().productId(1L).quantity(2).build()
			))
			.build();
		given(objectMapper.writeValueAsString(any())).willReturn("{\"orderId\":100}");

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

		// when
		stockRestoreOutboxCreateService.createOutboxEvent(command);

		// then
		then(outboxEventRepository).should().save(captor.capture());
		OutboxEvent saved = captor.getValue();
		assertThat(saved.getEventId()).isNotBlank();
		assertThat(saved.getEventType()).isEqualTo(OutboxEventType.STOCK_RESTORE_REQUESTED);
		assertThat(saved.getAggregateType()).isEqualTo(OutboxAggregateType.ORDER);
		assertThat(saved.getAggregateId()).isEqualTo(100L);
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getAttemptCount()).isZero();
		assertThat(saved.getNextRetryAt()).isEqualTo(requestedAt);
		assertThat(saved.getPayload()).isEqualTo("{\"orderId\":100}");
	}

	@DisplayName("MDC에 유효한 traceId가 있으면 outbox에 traceId를 저장한다")
	@Test
	void createOutboxEvent_whenValidTraceIdInMdc_saveTraceId() throws Exception {
		// given
		String traceId = "trace-abc-111";
		LogContext.putTraceId(traceId);
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(now.plusHours(1))
			.items(List.of(
				StockRestoreOutboxCreateCommand.Item.builder().productId(1L).quantity(2).build()
			))
			.build();
		given(objectMapper.writeValueAsString(any())).willReturn("{\"orderId\":100}");

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

		// when
		stockRestoreOutboxCreateService.createOutboxEvent(command);

		// then
		then(outboxEventRepository).should().save(captor.capture());
		assertThat(captor.getValue().getTraceId()).isEqualTo(traceId);
	}

	@DisplayName("MDC에 traceId가 없으면 outbox traceId를 null로 저장한다")
	@Test
	void createOutboxEvent_whenNoTraceIdInMdc_saveNullTraceId() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(now.plusHours(1))
			.items(List.of(
				StockRestoreOutboxCreateCommand.Item.builder().productId(1L).quantity(2).build()
			))
			.build();
		given(objectMapper.writeValueAsString(any())).willReturn("{\"orderId\":100}");

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

		// when
		stockRestoreOutboxCreateService.createOutboxEvent(command);

		// then
		then(outboxEventRepository).should().save(captor.capture());
		assertThat(captor.getValue().getTraceId()).isNull();
	}

	@DisplayName("MDC traceId 형식이 유효하지 않으면 outbox traceId를 null로 저장한다")
	@Test
	void createOutboxEvent_whenInvalidTraceIdInMdc_saveNullTraceId() throws Exception {
		// given
		LogContext.putTraceId("invalid trace id with spaces!");
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(now.plusHours(1))
			.items(List.of(
				StockRestoreOutboxCreateCommand.Item.builder().productId(1L).quantity(2).build()
			))
			.build();
		given(objectMapper.writeValueAsString(any())).willReturn("{\"orderId\":100}");

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

		// when
		stockRestoreOutboxCreateService.createOutboxEvent(command);

		// then
		then(outboxEventRepository).should().save(captor.capture());
		assertThat(captor.getValue().getTraceId()).isNull();
	}

	@DisplayName("재고 복구 outbox 이벤트 생성 시 payload 직렬화에 실패하면 예외가 발생한다")
	@Test
	void createOutboxEvent_whenSerializeFails_throwIllegalStateException() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(now.plusHours(1).plusMinutes(5))
			.items(List.of())
			.build();
		given(objectMapper.writeValueAsString(any())).willThrow(new JsonProcessingException("boom") {});

		// when // then
		assertThatThrownBy(() -> stockRestoreOutboxCreateService.createOutboxEvent(command))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to serialize outbox payload");
	}
}
