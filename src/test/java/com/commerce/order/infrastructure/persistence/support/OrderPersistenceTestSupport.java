package com.commerce.order.infrastructure.persistence.support;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.JpaOrderItemRepository;
import com.commerce.order.infrastructure.JpaOrderRepository;

import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

@TestComponent
@RequiredArgsConstructor
public class OrderPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaOrderRepository orderRepository;
	private final JpaOrderItemRepository orderItemRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.ORDER;
	}

	@Override
	public void deleteAllInBatch() {
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
	}

	public Order save(Order order) {
		return orderRepository.save(order);
	}

	public Order saveAndFlush(Order order) {
		return orderRepository.saveAndFlush(order);
	}

	public Optional<Order> findById(Long orderId) {
		return orderRepository.findById(orderId);
	}

	public List<Order> findAllById(List<Long> orderIds) {
		return orderRepository.findAllById(orderIds);
	}

	public Optional<Order> findByMerchantPayKey(String merchantPayKey) {
		return orderRepository.findByMerchantPayKey(merchantPayKey);
	}

	public long count() {
		return orderRepository.count();
	}

	public long countItems() {
		return orderItemRepository.count();
	}

	public OrderStatus getOrderStatusByMerchantPayKey(String merchantPayKey) {
		return findByMerchantPayKey(merchantPayKey).orElseThrow().getStatus();
	}
}
