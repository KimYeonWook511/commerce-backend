package com.commerce.order.infrastructure.persistence.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.JpaOrderItemCancellationRepository;
import com.commerce.order.infrastructure.persistence.JpaOrderItemRepository;
import com.commerce.order.infrastructure.persistence.JpaOrderRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

@TestComponent
@RequiredArgsConstructor
public class OrderPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaOrderRepository orderRepository;
	private final JpaOrderItemRepository orderItemRepository;
	private final JpaOrderItemCancellationRepository orderItemCancellationRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.ORDER;
	}

	@Override
	public void deleteAllInBatch() {
		// 취소 품목 내역이 주문 품목에 외래 키를 걸므로 그 앞에 지운다.
		orderItemCancellationRepository.deleteAllInBatch();
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

	public long count() {
		return orderRepository.count();
	}

	public long countItems() {
		return orderItemRepository.count();
	}

	/** 상태는 그대로 두고 주문 행의 버전만 올려 커밋한다. 다른 트랜잭션이 그 행을 먼저 바꾼 상황을 만든다 */
	@Transactional
	public void incrementVersion(Long orderId) {
		entityManager.createQuery("update Order o set o.version = o.version + 1 where o.id = :orderId")
			.setParameter("orderId", orderId)
			.executeUpdate();
	}

	public OrderStatus getOrderStatusById(Long orderId) {
		return findById(orderId).orElseThrow().getStatus();
	}

	public int getCancelledQuantity(Long orderItemId) {
		return orderItemRepository.findById(orderItemId).orElseThrow().getCancelledQuantity();
	}

	public long countCancellations() {
		return orderItemCancellationRepository.count();
	}

	/**
	 * 그 환불이 어느 주문 품목을 몇 개 취소했는지 읽는다. 값만 뽑아 오므로 지연 로딩 연관을 건드리지
	 * 않고 트랜잭션 밖에서도 볼 수 있다.
	 */
	public Map<Long, Integer> findCancelledQuantitiesByRefundId(Long refundId) {
		List<Object[]> rows = entityManager.createQuery("""
			select c.orderItem.id, c.quantity
			from OrderItemCancellation c
			where c.refundId = :refundId
			""", Object[].class)
			.setParameter("refundId", refundId)
			.getResultList();

		Map<Long, Integer> quantities = new HashMap<>();
		rows.forEach(row -> quantities.put((Long) row[0], (Integer) row[1]));
		return quantities;
	}
}
