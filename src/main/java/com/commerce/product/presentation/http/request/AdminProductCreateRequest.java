package com.commerce.product.presentation.http.request;

import com.commerce.product.application.command.AdminProductCreateCommand;
import com.commerce.product.domain.ProductStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

	@NotNull(message = "판매 상태는 필수입니다")
	private ProductStatus status;

	public AdminProductCreateCommand toCommand() {
		return AdminProductCreateCommand.builder()
			.name(name)
			.price(price)
			.description(description)
			.imageUrl(imageUrl)
			.status(status)
			.build();
	}
}
