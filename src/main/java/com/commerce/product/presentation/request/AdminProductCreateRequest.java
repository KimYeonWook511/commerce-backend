package com.commerce.product.presentation.request;

import com.commerce.product.application.command.AdminProductCreateCommand;
import com.commerce.product.domain.ProductStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class AdminProductCreateRequest {

	@NotBlank(message = "상품명은 필수입니다")
	private String name;

	@NotNull(message = "가격은 필수입니다")
	@Positive(message = "가격은 양수여야 합니다")
	private Integer price;

	private String description;

	private String imageUrl;

	@NotBlank(message = "판매 상태는 필수입니다")
	@Pattern(regexp = "ON_SALE|SOLD_OUT|STOPPED", message = "판매 상태가 올바르지 않습니다")
	private String status;

	public AdminProductCreateCommand toCommand() {
		return AdminProductCreateCommand.builder()
			.name(name)
			.price(price)
			.description(description)
			.imageUrl(imageUrl)
			.status(ProductStatus.valueOf(status))
			.build();
	}
}
