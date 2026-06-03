package com.commerce.product.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepository {

	private final JpaProductRepository jpaProductRepository;

	@Override
	public Product save(Product product) {
		return jpaProductRepository.save(product);
	}

	@Override
	public List<Product> findAllById(Iterable<Long> productIds) {
		return jpaProductRepository.findAllById(productIds);
	}

	@Override
	public Optional<Product> findById(Long productId) {
		return jpaProductRepository.findById(productId);
	}

	@Override
	public List<Product> findVisibleProducts(List<ProductStatus> statuses) {
		return jpaProductRepository.findVisibleProducts(statuses);
	}

	@Override
	public Optional<Product> findVisibleProduct(Long productId, List<ProductStatus> statuses) {
		return jpaProductRepository.findVisibleProduct(productId, statuses);
	}

	@Override
	public Optional<Product> findNotDeletedProduct(Long productId) {
		return jpaProductRepository.findNotDeletedProduct(productId);
	}

	@Override
	public boolean existsById(Long productId) {
		return jpaProductRepository.existsById(productId);
	}
}
