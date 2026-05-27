package com.commerce.cart.application;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.presentation.request.CartItemAddRequest;

import lombok.RequiredArgsConstructor;

/**
 * cart 항목 추가 흐름의 outer Service.
 *
 * <p>본 Service는 트랜잭션 어노테이션을 부착하지 않는다 (ADR-021).
 * 실제 트랜잭션 경계는 {@link AddCartItemProcessor}가 책임지며,
 * 본 Service는 cart phase ADR 결정 8에 따른 낙관적 락 retry loop만 담당한다.
 *
 * <p>retry 대상은 {@link ObjectOptimisticLockingFailureException}만이다.
 * 신규 항목 동시 insert race는 ADR-011 안전망 500 정책을 유지한다.
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
