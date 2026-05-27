package com.commerce.cart.application;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.presentation.request.CartItemAddRequest;

import lombok.RequiredArgsConstructor;

/**
 * cart 항목 추가 outer Service. retry loop 전담. 정책: cart adr 결정 8.
 */
@Service
@RequiredArgsConstructor
public class AddCartItemService {

	private static final int MAX_RETRY = 3;

	private final AddCartItemProcessor processor;

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
