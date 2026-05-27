package com.commerce.cart.application;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.presentation.request.CartItemUpdateRequest;

import lombok.RequiredArgsConstructor;

/**
 * cart 항목 수량 절대값 변경 흐름의 outer Service.
 *
 * <p>본 Service는 트랜잭션 어노테이션을 부착하지 않는다 (ADR-021).
 * 실제 트랜잭션 경계는 {@link UpdateCartItemQuantityProcessor}가 책임지며,
 * 본 Service는 cart phase ADR 결정 8에 따른 낙관적 락 retry loop만 담당한다.
 *
 * <p>PATCH는 절대값 변경 의미라 동시 요청 race가 발생해도 retry로 last-write-wins이 자연스럽게 달성된다.
 */
@Service
@RequiredArgsConstructor
public class UpdateCartItemQuantityService {

	private static final int MAX_RETRY = 3;

	private final UpdateCartItemQuantityProcessor processor;

	public CartItemSummaryResult update(Long memberId, Long productId, CartItemUpdateRequest request) {
		for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
			try {
				return processor.execute(memberId, productId, request);
			} catch (ObjectOptimisticLockingFailureException ex) {
				if (attempt == MAX_RETRY) {
					throw ex;
				}
			}
		}
		throw new IllegalStateException("unreachable");
	}
}
