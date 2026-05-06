package com.commerce.stock.presentation.request;

import com.commerce.stock.application.command.AdminStockCreateCommand;
import com.commerce.stock.domain.StockAdjustmentReason;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class AdminStockCreateRequest {

	@NotNull(message = "재고 수량은 필수입니다")
	@Min(value = 0, message = "재고 수량은 0 이상이어야 합니다")
	private Integer quantity;

	@NotBlank(message = "재고 변경 사유는 필수입니다")
	@Pattern(
		regexp = StockRequestValidation.ADJUSTMENT_REASON_PATTERN,
		message = "재고 변경 사유가 올바르지 않습니다"
	)
	private String reason;

	public AdminStockCreateCommand toCommand(Long productId, Long adminMemberId) {
		return AdminStockCreateCommand.builder()
			.productId(productId)
			.quantity(quantity)
			.reason(StockAdjustmentReason.valueOf(reason))
			.adminMemberId(adminMemberId)
			.build();
	}
}
