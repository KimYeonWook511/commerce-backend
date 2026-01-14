package com.commerce.orderitem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.orderitem.domain.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
