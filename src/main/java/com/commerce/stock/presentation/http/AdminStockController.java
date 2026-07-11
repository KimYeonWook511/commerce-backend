package com.commerce.stock.presentation.http;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.security.annotation.RequireRole;
import com.commerce.common.security.annotation.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.common.security.Role;
import com.commerce.stock.application.service.AdminDecreaseStockService;
import com.commerce.stock.application.service.AdminGetStockHistoryService;
import com.commerce.stock.application.service.AdminIncreaseStockService;
import com.commerce.stock.application.service.AdminInitializeStockService;
import com.commerce.stock.application.dto.AdminStockResult;
import com.commerce.stock.application.dto.StockHistoryResult;
import com.commerce.stock.presentation.http.request.AdminStockAdjustRequest;
import com.commerce.stock.presentation.http.request.AdminStockCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/products/{productId}/stock")
public class AdminStockController {

	private final AdminInitializeStockService adminInitializeStockService;
	private final AdminIncreaseStockService adminIncreaseStockService;
	private final AdminDecreaseStockService adminDecreaseStockService;
	private final AdminGetStockHistoryService adminGetStockHistoryService;

	@PostMapping
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> createInitialStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockCreateRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = adminInitializeStockService.createInitialStock(request.toCommand(productId, adminMemberId));

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.of(result));
	}

	@PostMapping("/increase")
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> increaseStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockAdjustRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = adminIncreaseStockService.increaseByAdmin(request.toCommand(productId, adminMemberId));

		return ResponseEntity.ok(ApiResponse.of(result));
	}

	@PostMapping("/decrease")
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> decreaseStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockAdjustRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = adminDecreaseStockService.decreaseByAdmin(request.toCommand(productId, adminMemberId));

		return ResponseEntity.ok(ApiResponse.of(result));
	}

	@GetMapping("/histories")
	@RequireRole(Role.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<List<StockHistoryResult>>> getStockHistories(
		@PathVariable Long productId
	) {
		validateProductId(productId);

		List<StockHistoryResult> results = adminGetStockHistoryService.getHistoriesByProductId(productId);

		return ResponseEntity.ok(ApiResponse.of(results));
	}

	private void validateProductId(Long productId) {
		if (productId == null || productId < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
	}
}
