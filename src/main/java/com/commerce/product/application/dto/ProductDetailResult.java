package com.commerce.product.application.dto;

import com.commerce.product.domain.Product;

import lombok.Builder;
import lombok.Getter;

@Getter
public class ProductDetailResult {

	private Long productId;
	private String name;
	private int price;
	private int stockQuantity;

	@Builder
	private ProductDetailResult(Long productId, String name, int price, int stockQuantity) {
		this.productId = productId;
		this.name = name;
		this.price = price;
		this.stockQuantity = stockQuantity;
	}

	public static ProductDetailResult from(Product product, int stockQuantity) {
		return ProductDetailResult.builder()
			.productId(product.getId())
			.name(product.getName())
			.price(product.getPrice())
			.stockQuantity(stockQuantity)
			.build();
	}
}
