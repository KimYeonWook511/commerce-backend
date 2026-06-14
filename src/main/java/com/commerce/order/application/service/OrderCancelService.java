package com.commerce.order.application.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.application.result.OrderCancelResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderItem;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.stock.application.service.StockInventoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderCancelService {

	private final OrderRepository orderRepository;
	private final StockInventoryService stockInventoryService;

	@Transactional
	public OrderCancelResult cancelOrder(Long memberId, Long orderId) {
		Order order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		order.cancel();

		List<OrderItem> sortedItems = order.getOrderItems().stream()
			.sorted(Comparator.comparing(OrderItem::getProductId))
			.toList();

		sortedItems.forEach(item ->
			stockInventoryService.increase(item.getProductId(), item.getQuantity())
		);

		log.info("주문 취소 orderId={} memberId={} itemCount={}",
			order.getId(), memberId, sortedItems.size());

		return OrderCancelResult.from(order);
	}
}
