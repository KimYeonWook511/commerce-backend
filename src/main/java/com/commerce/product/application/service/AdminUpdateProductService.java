package com.commerce.product.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.application.dto.AdminProductResult;
import com.commerce.product.application.dto.AdminProductUpdateCommand;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUpdateProductService {

	private final ProductRepository productRepository;

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

	private Product findNotDeletedProduct(Long productId) {
		return productRepository.findNotDeletedProduct(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}
}
