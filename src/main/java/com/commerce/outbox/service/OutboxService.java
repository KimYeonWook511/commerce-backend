package com.commerce.outbox.service;

import org.springframework.stereotype.Service;

import com.commerce.outbox.stock.service.StockRestoreOutboxService;
import com.commerce.outbox.stock.service.command.StockRestoreOutboxCreateCommand;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OutboxService {

	private final StockRestoreOutboxService stockRestoreOutboxService;

	public void createStockRestoreOutboxEvent(StockRestoreOutboxCreateCommand command) {
		stockRestoreOutboxService.createOutboxEvent(command);
	}

}
