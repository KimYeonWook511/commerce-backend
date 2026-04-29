package com.commerce.product.domain;

import java.util.List;

public enum ProductStatus {
	ON_SALE,
	SOLD_OUT,
	STOPPED;

	private static final List<ProductStatus> PUBLIC_STATUSES = List.of(ON_SALE, SOLD_OUT);

	public boolean isPubliclyVisible() {
		return PUBLIC_STATUSES.contains(this);
	}

	public static List<ProductStatus> publicStatuses() {
		return PUBLIC_STATUSES;
	}
}
