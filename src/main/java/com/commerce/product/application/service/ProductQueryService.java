package com.commerce.product.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.application.dto.ProductDetailResult;
import com.commerce.product.application.dto.ProductSummaryResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;
import com.commerce.stock.domain.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductQueryService {

	private final ProductRepository productRepository;
	private final StockRepository stockRepository;

	@Transactional(readOnly = true)
	public List<ProductSummaryResult> getProducts() {
		return productRepository.findVisibleProducts(ProductStatus.publicStatuses())
			.stream()
			.map(ProductSummaryResult::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public ProductDetailResult getProduct(Long productId) {
		Product product = productRepository.findVisibleProduct(productId, ProductStatus.publicStatuses())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		int stockQuantity = stockRepository.findByProductId(productId)
			.map(stock -> stock.getQuantity())
			.orElse(0);

		return ProductDetailResult.from(product, stockQuantity);
	}
}
