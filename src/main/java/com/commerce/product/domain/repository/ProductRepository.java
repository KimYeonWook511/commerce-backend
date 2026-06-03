package com.commerce.product.domain.repository;

import java.util.List;
import java.util.Optional;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

public interface ProductRepository {

	Product save(Product product);

	List<Product> findAllById(Iterable<Long> productIds);

	Optional<Product> findById(Long productId);

	List<Product> findVisibleProducts(List<ProductStatus> statuses);

	Optional<Product> findVisibleProduct(Long productId, List<ProductStatus> statuses);

	Optional<Product> findNotDeletedProduct(Long productId);

	boolean existsById(Long productId);
}
