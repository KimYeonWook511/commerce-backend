package com.commerce.outbox.stock.application.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.ProcessedEventConsumerType;
import com.commerce.outbox.domain.repository.ProcessedEventRepository;
import com.commerce.outbox.stock.application.command.StockRestoreConsumeCommand;
import com.commerce.stock.application.service.StockInventoryService;

@ExtendWith(MockitoExtension.class)
class StockRestoreOutboxConsumeServiceTest {

	@Mock
	private ProcessedEventRepository processedEventRepository;

	@Mock
	private StockInventoryService stockService;

	@InjectMocks
	private StockRestoreOutboxConsumeService stockRestoreOutboxConsumeService;

	@DisplayName("최초 소비면 ProcessedEvent를 저장하고 재고를 복구한다")
	@Test
	void consume_whenFirstMessage_restoreStockAndSaveProcessedEvent() {
		// given
		StockRestoreConsumeCommand command = StockRestoreConsumeCommand.builder()
			.eventId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
			.items(List.of(
				StockRestoreConsumeCommand.Item.builder().productId(1L).quantity(2).build(),
				StockRestoreConsumeCommand.Item.builder().productId(2L).quantity(3).build()
			))
			.build();
		given(processedEventRepository.existsByEventIdAndConsumerType(command.getEventId(),
			ProcessedEventConsumerType.STOCK_RESTORE)).willReturn(false);

		// when
		stockRestoreOutboxConsumeService.consume(command);

		// then
		ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
		then(processedEventRepository).should().save(captor.capture());
		ProcessedEvent processedEvent = captor.getValue();
		org.assertj.core.api.Assertions.assertThat(processedEvent.getEventId()).isEqualTo(command.getEventId());
		org.assertj.core.api.Assertions.assertThat(processedEvent.getConsumerType())
			.isEqualTo(ProcessedEventConsumerType.STOCK_RESTORE);

		then(stockService).should().increase(1L, 2);
		then(stockService).should().increase(2L, 3);
	}

	@DisplayName("중복 소비면 사전 체크에서 걸러내고 저장과 재고 복구를 건너뛴다")
	@Test
	void consume_whenDuplicatedMessage_skipRestoreStock() {
		// given
		StockRestoreConsumeCommand command = StockRestoreConsumeCommand.builder()
			.eventId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
			.items(List.of(StockRestoreConsumeCommand.Item.builder().productId(1L).quantity(2).build()))
			.build();
		given(processedEventRepository.existsByEventIdAndConsumerType(command.getEventId(),
			ProcessedEventConsumerType.STOCK_RESTORE)).willReturn(true);

		// when
		stockRestoreOutboxConsumeService.consume(command);

		// then
		then(processedEventRepository).should(never()).save(org.mockito.ArgumentMatchers.any(ProcessedEvent.class));
		then(stockService).should(never()).increase(org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyInt());
	}

	@DisplayName("재고 복구 중 예외가 발생하면 예외를 전파한다")
	@Test
	void consume_whenRestoreStockFails_throwException() {
		// given
		StockRestoreConsumeCommand command = StockRestoreConsumeCommand.builder()
			.eventId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
			.items(List.of(StockRestoreConsumeCommand.Item.builder().productId(1L).quantity(2).build()))
			.build();
		willThrow(new IllegalStateException("restore failed"))
			.given(stockService)
			.increase(1L, 2);

		// when & then
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> stockRestoreOutboxConsumeService.consume(command))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("restore failed");
	}
}
