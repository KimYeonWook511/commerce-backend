package com.commerce.stock.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.stock.domain.Stock;

import jakarta.persistence.LockModeType;

public interface JpaStockRepository extends JpaRepository<Stock, Long> {

	Optional<Stock> findByProductId(Long productId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Stock s where s.productId = :productId")
	Optional<Stock> findByProductIdWithPessimisticLock(@Param("productId") Long productId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Stock s where s.productId in :productIds")
	List<Stock> findAllByProductIdInWithPessimisticLock(@Param("productIds") List<Long> productIds);
}
