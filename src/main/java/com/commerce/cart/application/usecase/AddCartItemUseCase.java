package com.commerce.cart.application.usecase;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.application.service.AddCartItemService;
import com.commerce.cart.presentation.http.request.CartItemAddRequest;

import lombok.RequiredArgsConstructor;

/**
 * cart 항목 추가 outer UseCase. retry loop 전담. 정책: cart adr 결정 8.
 */
@Component
@RequiredArgsConstructor
public class AddCartItemUseCase {

	private static final int MAX_RETRY = 3;

	private final AddCartItemService processor;

	public CartItemSummaryResult add(Long memberId, CartItemAddRequest request) {
		for (int attempt = 1; attempt < MAX_RETRY; attempt++) {
			try {
				return processor.execute(memberId, request);
			} catch (ObjectOptimisticLockingFailureException ignored) {
				// retry
			}
		}
		return processor.execute(memberId, request);
	}
}
