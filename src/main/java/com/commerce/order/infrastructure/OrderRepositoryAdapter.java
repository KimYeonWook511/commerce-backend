package com.commerce.order.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryAdapter implements OrderRepository {

	private final JpaOrderRepository jpaOrderRepository;

	@Override
	public Order save(Order order) {
		return jpaOrderRepository.save(order);
	}

	@Override
	public Optional<Order> findById(Long orderId) {
		return jpaOrderRepository.findById(orderId);
	}

	@Override
	public Optional<Order> findByIdForUpdate(Long orderId) {
		return jpaOrderRepository.findByIdForUpdate(orderId);
	}

	@Override
	public Optional<Order> findByIdAndMemberId(Long orderId, Long memberId) {
		return jpaOrderRepository.findByIdAndMemberId(orderId, memberId);
	}

	@Override
	public Optional<Order> findByIdAndMemberIdWithItems(Long orderId, Long memberId) {
		return jpaOrderRepository.findByIdAndMemberIdWithItems(orderId, memberId);
	}

	@Override
	public Optional<Order> findByIdWithItems(Long orderId) {
		return jpaOrderRepository.findByIdWithItems(orderId);
	}

	@Override
	public List<Order> findExpiredOrdersAfterId(
		OrderStatus status,
		LocalDateTime cutoff,
		Long lastId,
		int limit
	) {
		return jpaOrderRepository.findExpiredOrdersAfterId(
			status,
			cutoff,
			lastId,
			PageRequest.of(0, limit, Sort.by("id").ascending())
		);
	}

	@Override
	public Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey) {
		return jpaOrderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
	}
}
