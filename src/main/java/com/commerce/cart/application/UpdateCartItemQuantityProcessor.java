package com.commerce.cart.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.result.CartItemAddedView;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;
import com.commerce.cart.presentation.request.CartItemUpdateRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cart 항목 수량 절대값 변경 트랜잭션 경계.
 *
 * <p>{@link UpdateCartItemQuantityService}의 retry loop가 매 attempt마다 빈 경계를 넘어 호출하므로
 * 새 트랜잭션·새 persistence context로 진입한다.
 * 트랜잭션 정책은 ADR-021을 따르며, 영속화 호출은 ADR-022를 따른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateCartItemQuantityProcessor {

	private final CartItemRepository cartItemRepository;

	@Transactional
	public CartItemAddedView execute(Long memberId, Long productId, CartItemUpdateRequest request) {
		int quantity = request.getQuantity();

		CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));
		cartItem.changeQuantity(quantity);
		cartItemRepository.save(cartItem);

		log.info("장바구니 수량 변경 memberId={} productId={} quantity={}",
			memberId, productId, cartItem.getQuantity());

		return CartItemAddedView.from(cartItem);
	}
}
