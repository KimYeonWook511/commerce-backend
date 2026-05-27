package com.commerce.cart.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cart 항목 삭제 흐름.
 *
 * <p>cart phase ADR 결정 6-4에 따라 미존재 항목은 {@code CART_ITEM_NOT_FOUND} 4xx로 응답한다.
 * PATCH({@link UpdateCartItemQuantityService})와 정책 일관성을 맞추고,
 * 0 row 삭제 시 silent log miss가 발생하는 회귀를 자동 해결한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoveCartItemService {

	private final CartItemRepository cartItemRepository;

	@Transactional
	public void remove(Long memberId, Long productId) {
		cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));

		cartItemRepository.deleteByMemberIdAndProductId(memberId, productId);

		log.info("장바구니 항목 삭제 memberId={} productId={}", memberId, productId);
	}
}
