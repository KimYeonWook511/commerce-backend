package com.commerce.cart.application;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;

import lombok.RequiredArgsConstructor;

/**
 * cart 항목 수량 절대값 변경 outer Service. retry loop 전담. 정책: cart adr 결정 8.
 */
@Service
@RequiredArgsConstructor
public class UpdateCartItemQuantityService {

	private static final int MAX_RETRY = 3;

	private final UpdateCartItemQuantityProcessor processor;

	public CartItemSummaryResult update(Long memberId, Long productId, CartItemUpdateRequest request) {
		for (int attempt = 1; attempt < MAX_RETRY; attempt++) {
			try {
				return processor.execute(memberId, productId, request);
			} catch (ObjectOptimisticLockingFailureException ignored) {
				// retry
			}
		}
		return processor.execute(memberId, productId, request);
	}
}
