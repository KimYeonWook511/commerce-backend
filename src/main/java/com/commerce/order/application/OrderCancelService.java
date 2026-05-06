package com.commerce.order.application;

import java.util.Comparator;
import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.application.result.OrderCancelResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.orderitem.domain.OrderItem;
import com.commerce.stock.application.StockInventoryService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderCancelService {

	private final OrderRepository orderRepository;
	private final StockInventoryService stockInventoryService;

	@Transactional
	public OrderCancelResult cancelOrder(Long memberId, Long orderId) {
		try {
			Order order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
				.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

			order.cancel();

			List<OrderItem> sortedItems = order.getOrderItems().stream()
				.sorted(Comparator.comparing(item -> item.getProduct().getId()))
				.toList();

			sortedItems.forEach(item ->
				stockInventoryService.increase(item.getProduct().getId(), item.getQuantity())
			);

			orderRepository.flush();

			return OrderCancelResult.from(order);
		} catch (OptimisticLockingFailureException ex) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}
	}
}
