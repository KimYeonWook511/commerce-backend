package com.commerce.product.presentation.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.security.annotation.RequireRole;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.common.security.Role;
import com.commerce.product.application.service.AdminCreateProductService;
import com.commerce.product.application.service.AdminUpdateProductService;
import com.commerce.product.application.service.AdminDeleteProductService;
import com.commerce.product.application.dto.AdminProductDeleteResult;
import com.commerce.product.application.dto.AdminProductResult;
import com.commerce.product.presentation.http.request.AdminProductCreateRequest;
import com.commerce.product.presentation.http.request.AdminProductUpdateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/products")
public class AdminProductController {

	private final AdminCreateProductService adminCreateProductService;
	private final AdminUpdateProductService adminUpdateProductService;
	private final AdminDeleteProductService adminDeleteProductService;

	@PostMapping
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminProductResult>> createProduct(
		@Valid @RequestBody AdminProductCreateRequest request
	) {
		AdminProductResult result = adminCreateProductService.createProduct(request.toCommand());

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.of(result));
	}

	@PatchMapping("/{productId}")
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminProductResult>> updateProduct(
		@PathVariable Long productId,
		@Valid @RequestBody AdminProductUpdateRequest request
	) {
		validateProductId(productId);

		AdminProductResult result = adminUpdateProductService.updateProduct(request.toCommand(productId));

		return ResponseEntity.status(HttpStatus.OK)
			.body(ApiResponse.of(result));
	}

	@DeleteMapping("/{productId}")
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminProductDeleteResult>> deleteProduct(@PathVariable Long productId) {
		validateProductId(productId);

		AdminProductDeleteResult result = adminDeleteProductService.deleteProduct(productId);

		return ResponseEntity.status(HttpStatus.OK)
			.body(ApiResponse.of(result));
	}

	private void validateProductId(Long productId) {
		if (productId == null || productId < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
	}
}
