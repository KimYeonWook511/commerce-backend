package com.commerce.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
