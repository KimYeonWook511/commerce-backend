package com.commerce.stock.presentation.http.request;

import com.commerce.stock.application.command.AdminStockAdjustCommand;
import com.commerce.stock.domain.StockAdjustmentReason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class AdminStockAdjustRequest {

	@NotNull(message = "재고 수량은 필수입니다")
	@Positive(message = "재고 수량은 0보다 커야 합니다")
	private Integer quantity;

	@NotBlank(message = "재고 변경 사유는 필수입니다")
	@Pattern(
		regexp = StockRequestValidation.ADJUSTMENT_REASON_PATTERN,
		message = "재고 변경 사유가 올바르지 않습니다"
	)
	private String reason;

	public AdminStockAdjustCommand toCommand(Long productId, Long adminMemberId) {
		return AdminStockAdjustCommand.builder()
			.productId(productId)
			.quantity(quantity)
			.reason(StockAdjustmentReason.valueOf(reason))
			.adminMemberId(adminMemberId)
			.build();
	}
}
