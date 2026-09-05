package com.commerce.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.order.domain.OrderItemCancellation;

public interface JpaOrderItemCancellationRepository extends JpaRepository<OrderItemCancellation, Long> {
}
