package com.commerce.product.integration.support;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.product.domain.Product;
import com.commerce.product.infrastructure.JpaProductRepository;
import com.commerce.test.support.CleanupOrder;
import com.commerce.test.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

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
