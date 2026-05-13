package com.commerce.outbox.application;

import org.springframework.stereotype.Service;

import com.commerce.outbox.stock.application.StockRestoreOutboxCreateService;
import com.commerce.outbox.stock.application.command.StockRestoreOutboxCreateCommand;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OutboxService {

	private final StockRestoreOutboxCreateService stockRestoreOutboxCreateService;

	public void createStockRestoreOutboxEvent(StockRestoreOutboxCreateCommand command) {
		stockRestoreOutboxCreateService.createOutboxEvent(command);
	}
}
