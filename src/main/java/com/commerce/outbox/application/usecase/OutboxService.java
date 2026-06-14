package com.commerce.outbox.application.usecase;

import org.springframework.stereotype.Service;

import com.commerce.outbox.stock.application.service.StockRestoreOutboxCreateService;
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
