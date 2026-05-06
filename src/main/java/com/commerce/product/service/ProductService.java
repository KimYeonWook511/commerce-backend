package com.commerce.product.service;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.product.service.command.AdminProductCreateCommand;
import com.commerce.product.service.command.AdminProductUpdateCommand;
import com.commerce.product.service.result.AdminProductDeleteResult;
import com.commerce.product.service.result.AdminProductResult;
import com.commerce.product.service.result.ProductDetailResult;
import com.commerce.product.service.result.ProductSummaryResult;
import com.commerce.stock.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProductService {

	private final ProductRepository productRepository;
	private final StockRepository stockRepository;

	@Transactional
	public AdminProductResult createProduct(AdminProductCreateCommand command) {
		Product product = Product.builder()
			.name(command.getName())
			.price(command.getPrice())
			.description(command.getDescription())
			.imageUrl(command.getImageUrl())
			.status(command.getStatus())
			.build();

		return AdminProductResult.from(productRepository.save(product));
	}

	@Transactional
	public AdminProductResult updateProduct(AdminProductUpdateCommand command) {
		Product product = findActiveProduct(command.getProductId());

		product.update(
			command.getName(),
			command.getPrice(),
			command.getDescription(),
			command.getImageUrl(),
			command.getStatus()
		);

		return AdminProductResult.from(product);
	}

	@Transactional
	public AdminProductDeleteResult deleteProduct(Long productId) {
		Product product = findActiveProduct(productId);
		product.softDelete();

		return AdminProductDeleteResult.of(product.getId());
	}

	@Transactional(readOnly = true)
	public List<ProductSummaryResult> getProducts() {
		return productRepository.findAllByDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(ProductStatus.publicStatuses())
			.stream()
			.map(ProductSummaryResult::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public ProductDetailResult getProduct(Long productId) {
		Product product = productRepository.findByIdAndDeletedAtIsNullAndStatusIn(productId, ProductStatus.publicStatuses())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		int stockQuantity = stockRepository.findByProductId(productId)
			.map(stock -> stock.getQuantity())
			.orElse(0);

		return ProductDetailResult.from(product, stockQuantity);
	}

	private Product findActiveProduct(Long productId) {
		return productRepository.findByIdAndDeletedAtIsNull(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}
}
