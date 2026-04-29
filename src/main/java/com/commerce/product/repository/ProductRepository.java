package com.commerce.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

public interface ProductRepository extends JpaRepository<Product, Long> {
	List<Product> findAllByOrderByCreatedAtDesc();

	List<Product> findAllByDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(List<ProductStatus> statuses);

	Optional<Product> findByIdAndDeletedAtIsNullAndStatusIn(Long id, List<ProductStatus> statuses);

	Optional<Product> findByIdAndDeletedAtIsNull(Long id);
}
