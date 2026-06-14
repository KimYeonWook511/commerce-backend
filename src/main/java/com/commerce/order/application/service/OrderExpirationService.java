package com.commerce.order.application.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.OrderItem;
import com.commerce.outbox.application.usecase.OutboxService;
import com.commerce.outbox.stock.application.command.StockRestoreOutboxCreateCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderExpirationService {

	private final OrderRepository orderRepository;
	private final OutboxService outboxService;

	@Transactional
	public void expireOrder(Long orderId, LocalDateTime requestedAt) {
		Order order = orderRepository.findByIdWithItems(orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		order.cancel();

		outboxService.createStockRestoreOutboxEvent(toStockRestoreOutboxCreateCommand(order, requestedAt));

		log.info("주문 만료 orderId={} itemCount={}", order.getId(), order.getOrderItems().size());
	}

	private StockRestoreOutboxCreateCommand toStockRestoreOutboxCreateCommand(Order order, LocalDateTime requestedAt) {
		Map<Long, Integer> mergedQuantities = new HashMap<>();
		for (OrderItem orderItem : order.getOrderItems()) {
			Long productId = orderItem.getProductId();
			mergedQuantities.merge(productId, orderItem.getQuantity(), Integer::sum);
		}

		List<StockRestoreOutboxCreateCommand.Item> items = mergedQuantities.entrySet().stream()
			.map(entry -> StockRestoreOutboxCreateCommand.Item.builder()
				.productId(entry.getKey())
				.quantity(entry.getValue())
				.build())
			.sorted(Comparator.comparing(StockRestoreOutboxCreateCommand.Item::getProductId))
			.toList();

		return StockRestoreOutboxCreateCommand.builder()
			.orderId(order.getId())
			.items(items)
			.requestedAt(requestedAt)
			.build();
	}
}
