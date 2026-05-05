package com.commerce.stock.presentation;

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
import com.commerce.stock.application.AdminStockService;
import com.commerce.stock.application.result.AdminStockResult;
import com.commerce.stock.application.result.StockHistoryResult;
import com.commerce.stock.presentation.request.AdminStockAdjustRequest;
import com.commerce.stock.presentation.request.AdminStockCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/products/{productId}/stock")
public class AdminStockController {

	private final AdminStockService adminStockService;

	@PostMapping
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<AdminStockResult>> createInitialStock(
		@PathVariable Long productId,
		@AuthenticatedMemberId Long adminMemberId,
		@Valid @RequestBody AdminStockCreateRequest request
	) {
		validateProductId(productId);

		AdminStockResult result = adminStockService.createInitialStock(request.toCommand(productId, adminMemberId));

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

		AdminStockResult result = adminStockService.increaseByAdmin(request.toCommand(productId, adminMemberId));

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

		AdminStockResult result = adminStockService.decreaseByAdmin(request.toCommand(productId, adminMemberId));

		return ResponseEntity.ok(ApiResponse.of(result));
	}

	@GetMapping("/histories")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public ResponseEntity<ApiResponse<List<StockHistoryResult>>> getStockHistories(
		@PathVariable Long productId
	) {
		validateProductId(productId);

		List<StockHistoryResult> results = adminStockService.getHistoriesByProductId(productId);

		return ResponseEntity.ok(ApiResponse.of(results));
	}

	private void validateProductId(Long productId) {
		if (productId == null || productId < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
	}
}
