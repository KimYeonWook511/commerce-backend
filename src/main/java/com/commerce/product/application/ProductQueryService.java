package com.commerce.product.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.application.result.ProductDetailResult;
import com.commerce.product.application.result.ProductSummaryResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.stock.domain.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

	private final ProductRepository productRepository;
	private final StockRepository stockRepository;

	public List<ProductSummaryResult> getProducts() {
		return productRepository.findVisibleProducts(ProductStatus.publicStatuses())
			.stream()
			.map(ProductSummaryResult::from)
			.toList();
	}

	public ProductDetailResult getProduct(Long productId) {
		Product product = productRepository.findVisibleProduct(productId, ProductStatus.publicStatuses())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		int stockQuantity = stockRepository.findByProductId(productId)
			.map(stock -> stock.getQuantity())
			.orElse(0);

		return ProductDetailResult.from(product, stockQuantity);
	}
}
