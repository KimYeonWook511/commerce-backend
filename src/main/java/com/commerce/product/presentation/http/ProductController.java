package com.commerce.product.presentation.http;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.product.application.ProductQueryService;
import com.commerce.product.application.result.ProductDetailResult;
import com.commerce.product.application.result.ProductSummaryResult;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/products")
public class ProductController {

	private final ProductQueryService productQueryService;

	@GetMapping
	public ResponseEntity<ApiResponse<List<ProductSummaryResult>>> getProducts() {
		return ResponseEntity.status(HttpStatus.OK)
			.body(ApiResponse.of(productQueryService.getProducts()));
	}

	@GetMapping("/{productId}")
	public ResponseEntity<ApiResponse<ProductDetailResult>> getProduct(@PathVariable Long productId) {
		if (productId == null || productId < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		return ResponseEntity.status(HttpStatus.OK)
			.body(ApiResponse.of(productQueryService.getProduct(productId)));
	}
}
