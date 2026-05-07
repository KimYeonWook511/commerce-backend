package com.commerce.product.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.commerce.security.annotation.RequireRole;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.member.domain.MemberRole;
import com.commerce.product.controller.request.AdminProductCreateRequest;
import com.commerce.product.controller.request.AdminProductUpdateRequest;
import com.commerce.product.service.ProductService;
import com.commerce.product.service.result.AdminProductDeleteResult;
import com.commerce.product.service.result.AdminProductResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RequestMapping("/admin/products")
public class AdminProductController {

	private final ProductService productService;

	@PostMapping
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminProductResult>> createProduct(
		@Valid @RequestBody AdminProductCreateRequest request
	) {
		AdminProductResult result = productService.createProduct(request.toCommand());

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.of(result));
	}

	@PatchMapping("/{productId}")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminProductResult>> updateProduct(
		@PathVariable Long productId,
		@Valid @RequestBody AdminProductUpdateRequest request
	) {
		validateProductId(productId);

		AdminProductResult result = productService.updateProduct(request.toCommand(productId));

		return ResponseEntity.status(HttpStatus.OK)
			.body(ApiResponse.of(result));
	}

	@DeleteMapping("/{productId}")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminProductDeleteResult>> deleteProduct(@PathVariable Long productId) {
		validateProductId(productId);

		AdminProductDeleteResult result = productService.deleteProduct(productId);

		return ResponseEntity.status(HttpStatus.OK)
			.body(ApiResponse.of(result));
	}

	private void validateProductId(Long productId) {
		if (productId == null || productId < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
	}
}
