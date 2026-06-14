package com.commerce.outbox.stock.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.log.LogContext;
import com.commerce.common.util.UlidGenerator;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.repository.OutboxEventRepository;
import com.commerce.outbox.stock.application.command.StockRestoreOutboxCreateCommand;
import com.commerce.outbox.stock.application.payload.StockRestoreRequestedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRestoreOutboxCreateService {

	private static final OutboxEventType STOCK_RESTORE_EVENT_TYPE = OutboxEventType.STOCK_RESTORE_REQUESTED;

	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	@Transactional
	public void createOutboxEvent(StockRestoreOutboxCreateCommand command) {
		String payload = serializePayload(StockRestoreRequestedPayload.from(command));
		OutboxEvent outboxEvent = OutboxEvent.createPending(
			UlidGenerator.generate(),
			STOCK_RESTORE_EVENT_TYPE,
			payload,
			command.getRequestedAt(),
			OutboxAggregateType.ORDER,
			command.getOrderId(),
			resolveTraceIdForStorage()
		);
		outboxEventRepository.save(outboxEvent);

		log.info("재고 복구 Outbox 발행 orderId={} itemCount={}",
			command.getOrderId(), command.getItems().size());
	}

	private String serializePayload(StockRestoreRequestedPayload payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize outbox payload", ex);
		}
	}

	private String resolveTraceIdForStorage() {
		String traceId = LogContext.getTraceId();
		return LogContext.isValidTraceId(traceId) ? traceId : null;
	}
}
