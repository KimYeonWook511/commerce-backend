package com.commerce.cart.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.result.CartItemAddedView;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;
import com.commerce.cart.presentation.request.CartItemUpdateRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UpdateCartItemQuantityService {

	private final CartItemRepository cartItemRepository;

	public CartItemAddedView update(Long memberId, Long productId, CartItemUpdateRequest request) {
		int quantity = request.getQuantity();

		CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));
		cartItem.changeQuantity(quantity);

		log.info("장바구니 수량 변경 memberId={} productId={} quantity={}",
			memberId, productId, cartItem.getQuantity());

		return CartItemAddedView.from(cartItem);
	}
}
