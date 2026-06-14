package com.commerce.outbox.stock.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.ProcessedEventConsumerType;
import com.commerce.outbox.domain.repository.ProcessedEventRepository;
import com.commerce.outbox.stock.application.dto.StockRestoreConsumeCommand;
import com.commerce.stock.application.service.StockInventoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockRestoreOutboxConsumeService {

	private static final ProcessedEventConsumerType CONSUMER_TYPE = ProcessedEventConsumerType.STOCK_RESTORE;

	private final ProcessedEventRepository processedEventRepository;
	private final StockInventoryService stockInventoryService;

	@Transactional
	public void consume(StockRestoreConsumeCommand command) {
		if (!markProcessed(command.getEventId())) {
			log.info("Skip duplicated stock restore event. eventId={}, consumerType={}",
				command.getEventId(), CONSUMER_TYPE);
			return;
		}

		restoreStock(command.getItems());
		log.info("Consumed stock restore event. eventId={}, consumerType={}, itemCount={}",
			command.getEventId(), CONSUMER_TYPE, command.getItems().size());
	}

	private boolean markProcessed(String eventId) {
		if (processedEventRepository.existsByEventIdAndConsumerType(eventId, CONSUMER_TYPE)) {
			return false;
		}
		ProcessedEvent processedEvent = ProcessedEvent.create(eventId, CONSUMER_TYPE);
		processedEventRepository.save(processedEvent);
		return true;
	}

	private void restoreStock(List<StockRestoreConsumeCommand.Item> items) {
		for (StockRestoreConsumeCommand.Item item : items) {
			stockInventoryService.increase(item.getProductId(), item.getQuantity());
		}
	}
}
