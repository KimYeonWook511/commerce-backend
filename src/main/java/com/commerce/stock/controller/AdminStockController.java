package com.commerce.stock.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.auth.interceptor.RequireRole;
import com.commerce.auth.resolver.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.member.domain.MemberRole;
import com.commerce.stock.controller.request.AdminStockAdjustRequest;
import com.commerce.stock.controller.request.AdminStockCreateRequest;
import com.commerce.stock.service.StockService;
import com.commerce.stock.service.result.AdminStockResult;
import com.commerce.stock.service.result.StockHistoryResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/products/{productId}/stock")
public class AdminStockController {

	private final StockService stockService;

	@PostMapping
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> createInitialStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockCreateRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = stockService.createInitialStock(request.toCommand(productId, adminMemberId));

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.of(result));
	}

	@PostMapping("/increase")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> increaseStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockAdjustRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = stockService.increaseByAdmin(request.toCommand(productId, adminMemberId));

		return ResponseEntity.ok(ApiResponse.of(result));
	}

	@PostMapping("/decrease")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> decreaseStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockAdjustRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = stockService.decreaseByAdmin(request.toCommand(productId, adminMemberId));

		return ResponseEntity.ok(ApiResponse.of(result));
	}

	@GetMapping("/histories")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<List<StockHistoryResult>>> getStockHistories(
		@PathVariable Long productId
	) {
		validateProductId(productId);

		List<StockHistoryResult> results = stockService.getHistoriesByProductId(productId);

		return ResponseEntity.ok(ApiResponse.of(results));
	}

	private void validateProductId(Long productId) {
		if (productId == null || productId < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
	}
}
