package com.commerce.product.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.product.service.result.ProductDetailResult;
import com.commerce.product.service.result.ProductSummaryResult;
import com.commerce.stock.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService {

	private final ProductRepository productRepository;
	private final StockRepository stockRepository;

	public List<ProductSummaryResult> getProducts() {
		return productRepository.findAllByOrderByCreatedAtDesc().stream()
			.map(ProductSummaryResult::from)
			.toList();
	}

	public ProductDetailResult getProduct(Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		int stockQuantity = stockRepository.findByProductId(productId)
			.map(stock -> stock.getQuantity())
			.orElse(0);

		return ProductDetailResult.from(product, stockQuantity);
	}
}
