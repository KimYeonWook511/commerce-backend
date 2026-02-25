package com.commerce.outbox.stock.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.ProcessedEventConsumerType;
import com.commerce.outbox.repository.ProcessedEventRepository;
import com.commerce.outbox.stock.service.command.StockRestoreConsumeCommand;
import com.commerce.stock.service.StockService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockRestoreOutboxConsumeService {

	private static final ProcessedEventConsumerType CONSUMER_TYPE = ProcessedEventConsumerType.STOCK_RESTORE;

	private final ProcessedEventRepository processedEventRepository;
	private final StockService stockService;

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
		try {
			ProcessedEvent processedEvent = ProcessedEvent.create(eventId, CONSUMER_TYPE);
			processedEventRepository.saveAndFlush(processedEvent); // 바로 commit은 안 되지만 유니크 인덱스 점유 (동일 키 insert는 lock 대기)
			return true;
		} catch (DataIntegrityViolationException ex) {
			return false;
		}
	}

	private void restoreStock(List<StockRestoreConsumeCommand.Item> items) {
		for (StockRestoreConsumeCommand.Item item : items) {
			stockService.increaseWithPessimisticLock(item.getProductId(), item.getQuantity());
		}
	}

}
