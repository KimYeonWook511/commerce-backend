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
 * cart 항목 삭제 Service. 정책: cart adr 결정 6-4.
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
