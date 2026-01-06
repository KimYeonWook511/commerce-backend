package com.commerce.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.stock.domain.Stock;

public interface StockRepository extends JpaRepository<Stock, Long> {
}
