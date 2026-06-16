package com.commerce.product.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.application.dto.AdminProductDeleteResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDeleteProductService {

	private final ProductRepository productRepository;

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
