package com.commerce.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.product.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
