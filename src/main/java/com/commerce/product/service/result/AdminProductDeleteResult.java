package com.commerce.product.service.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AdminProductDeleteResult {

	private Long productId;
	private boolean deleted;

	@Builder
	private AdminProductDeleteResult(Long productId, boolean deleted) {
		this.productId = productId;
		this.deleted = deleted;
	}

	public static AdminProductDeleteResult of(Long productId) {
		return AdminProductDeleteResult.builder()
			.productId(productId)
			.deleted(true)
			.build();
	}
}
