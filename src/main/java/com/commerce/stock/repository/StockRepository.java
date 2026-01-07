package com.commerce.stock.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.stock.domain.Stock;

public interface StockRepository extends JpaRepository<Stock, Long> {
	Optional<Stock> findByProductId(Long productId);
}
