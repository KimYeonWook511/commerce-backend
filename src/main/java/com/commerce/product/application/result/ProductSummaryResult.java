package com.commerce.product.application.result;

import com.commerce.product.domain.Product;

import lombok.Builder;
import lombok.Getter;

@Getter
public class ProductSummaryResult {

	private Long productId;
	private String name;
	private int price;

	@Builder
	private ProductSummaryResult(Long productId, String name, int price) {
		this.productId = productId;
		this.name = name;
		this.price = price;
	}

	public static ProductSummaryResult from(Product product) {
		return ProductSummaryResult.builder()
			.productId(product.getId())
			.name(product.getName())
			.price(product.getPrice())
			.build();
	}
}
