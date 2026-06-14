package com.commerce.cart.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.dto.CartItemSummaryResult;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.domain.exception.CartErrorCode;
import com.commerce.cart.domain.exception.CartException;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cart 항목 수량 절대값 변경 트랜잭션 경계. 정책: ADR-021, ADR-022, cart adr 결정 8.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateCartItemQuantityService {

	private final CartItemRepository cartItemRepository;

	@Transactional
	public CartItemSummaryResult execute(Long memberId, Long productId, CartItemUpdateRequest request) {
		int quantity = request.getQuantity();

		CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));
		cartItem.changeQuantity(quantity);
		cartItemRepository.save(cartItem);

		log.info("장바구니 수량 변경 memberId={} productId={} quantity={}",
			memberId, productId, cartItem.getQuantity());

		return CartItemSummaryResult.from(cartItem);
	}
}
