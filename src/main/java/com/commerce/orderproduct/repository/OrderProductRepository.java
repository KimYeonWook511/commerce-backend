package com.commerce.orderproduct.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.orderproduct.domain.OrderProduct;

public interface OrderProductRepository extends JpaRepository<OrderProduct, Long> {
}
