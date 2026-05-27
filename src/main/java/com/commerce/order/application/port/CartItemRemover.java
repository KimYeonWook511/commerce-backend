package com.commerce.order.application.port;

import java.util.List;

public interface CartItemRemover {

	void removeByMemberAndProducts(Long memberId, List<Long> productIds);
}
