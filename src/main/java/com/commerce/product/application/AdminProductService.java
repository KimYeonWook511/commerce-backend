package com.commerce.product.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.application.command.AdminProductCreateCommand;
import com.commerce.product.application.command.AdminProductUpdateCommand;
import com.commerce.product.application.result.AdminProductDeleteResult;
import com.commerce.product.application.result.AdminProductResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminProductService {

	private final ProductRepository productRepository;

	@Transactional
	public AdminProductResult createProduct(AdminProductCreateCommand command) {
		Product product = Product.builder()
			.name(command.getName())
			.price(command.getPrice())
			.description(command.getDescription())
			.imageUrl(command.getImageUrl())
			.status(command.getStatus())
			.build();

		Product savedProduct = productRepository.save(product);
		log.info("상품 생성 productId={} name={}", savedProduct.getId(), savedProduct.getName());
		return AdminProductResult.from(savedProduct);
	}

	@Transactional
	public AdminProductResult updateProduct(AdminProductUpdateCommand command) {
		Product product = findNotDeletedProduct(command.getProductId());

		product.update(
			command.getName(),
			command.getPrice(),
			command.getDescription(),
			command.getImageUrl(),
			command.getStatus()
		);

		log.info("상품 수정 productId={}", product.getId());
		return AdminProductResult.from(product);
	}

	@Transactional
	public AdminProductDeleteResult deleteProduct(Long productId) {
		Product product = findNotDeletedProduct(productId);
		product.softDelete();

		log.info("상품 삭제 productId={}", product.getId());
		return AdminProductDeleteResult.of(product.getId());
	}

	private Product findNotDeletedProduct(Long productId) {
		return productRepository.findNotDeletedProduct(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}
}
