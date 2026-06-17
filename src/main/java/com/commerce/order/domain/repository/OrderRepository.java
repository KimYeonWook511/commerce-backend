package com.commerce.order.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

public interface OrderRepository {

	Order save(Order order);

	Optional<Order> findById(Long orderId);

	Optional<Order> findByIdForUpdate(Long orderId);

	Optional<Order> findByIdAndMemberIdForUpdate(Long orderId, Long memberId);

	Optional<Order> findByIdAndMemberId(Long orderId, Long memberId);

	Optional<Order> findByIdAndMemberIdWithItems(Long orderId, Long memberId);

	Optional<Order> findByIdWithItems(Long orderId);

	List<Order> findExpiredOrdersAfterId(OrderStatus status, LocalDateTime cutoff, Long lastId, int limit);

	Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);
}
