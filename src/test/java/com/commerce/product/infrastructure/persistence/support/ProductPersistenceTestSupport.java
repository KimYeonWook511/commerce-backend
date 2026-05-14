package com.commerce.product.infrastructure.persistence.support;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.product.domain.Product;
import com.commerce.product.infrastructure.JpaProductRepository;

import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

@TestComponent
@RequiredArgsConstructor
public class ProductPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaProductRepository productRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.PRODUCT;
	}

	@Override
	public void deleteAllInBatch() {
		productRepository.deleteAllInBatch();
	}

	public Product save(Product product) {
		return productRepository.save(product);
	}
}
