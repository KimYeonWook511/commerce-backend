package com.commerce.order.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.order.domain.OrderItem;

public interface JpaOrderItemRepository extends JpaRepository<OrderItem, Long> {
}
