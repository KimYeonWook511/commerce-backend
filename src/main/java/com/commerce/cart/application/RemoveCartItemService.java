package com.commerce.cart.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cart 항목 삭제 흐름.
 *
 * <p>cart phase ADR 결정 6-4에 따라 미존재 항목은 {@code CART_ITEM_NOT_FOUND} 4xx로 응답한다.
 * find로 조회한 managed entity를 그대로 {@code delete(entity)}로 넘겨 persistence context와 동기되며,
 * {@code @Version} 체크가 적용되어 동시 DELETE race도 {@link org.springframework.orm.ObjectOptimisticLockingFailureException}으로 surface된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoveCartItemService {

	private final CartItemRepository cartItemRepository;

	@Transactional
	public void remove(Long memberId, Long productId) {
		CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));

		cartItemRepository.delete(cartItem);

		log.info("장바구니 항목 삭제 memberId={} productId={}", memberId, productId);
	}
}
