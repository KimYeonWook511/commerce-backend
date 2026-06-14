package com.commerce.product.application.result;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AdminProductResult {

	private Long productId;
	private String name;
	private int price;
	private String description;
	private String imageUrl;
	private ProductStatus status;

	@Builder
	private AdminProductResult(
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

	public static AdminProductResult from(Product product) {
		return AdminProductResult.builder()
			.productId(product.getId())
			.name(product.getName())
			.price(product.getPrice())
			.description(product.getDescription())
			.imageUrl(product.getImageUrl())
			.status(product.getStatus())
			.build();
	}
}
