package com.commerce.cart.application.usecase;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.commerce.cart.application.dto.CartItemSummaryResult;
import com.commerce.cart.application.service.UpdateCartItemQuantityService;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;

import lombok.RequiredArgsConstructor;

/**
 * cart 항목 수량 절대값 변경 outer UseCase. retry loop 전담. 정책: cart adr 결정 8.
 */
@Component
@RequiredArgsConstructor
public class UpdateCartItemQuantityUseCase {

	private static final int MAX_RETRY = 3;

	private final UpdateCartItemQuantityService processor;

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
