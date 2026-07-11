package com.commerce.product.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.application.dto.AdminProductCreateCommand;
import com.commerce.product.application.dto.AdminProductResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCreateProductService {

	private final ProductRepository productRepository;

	@Transactional
	public AdminProductResult createProduct(AdminProductCreateCommand command) {
		Product product = Product.create(
			command.getName(),
			command.getPrice(),
			command.getDescription(),
			command.getImageUrl(),
			command.getStatus()
		);

		Product savedProduct = productRepository.save(product);
		log.info("상품 생성 productId={} name={}", savedProduct.getId(), savedProduct.getName());
		return AdminProductResult.from(savedProduct);
	}
}
