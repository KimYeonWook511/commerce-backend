package com.commerce.product.application.dto;

import com.commerce.product.domain.ProductStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AdminProductUpdateCommand {

	private Long productId;
	private String name;
	private int price;
	private String description;
	private String imageUrl;
	private ProductStatus status;

	@Builder
	private AdminProductUpdateCommand(
		Long productId,
		String name,
		int price,
		String description,
		String imageUrl,
		ProductStatus status
	) {
		this.productId = productId;
		this.name = name;
		this.price = price;
		this.description = description;
		this.imageUrl = imageUrl;
		this.status = status;
	}
}
