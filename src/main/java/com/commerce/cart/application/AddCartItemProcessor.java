package com.commerce.cart.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.result.CartItemAddedView;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.presentation.request.CartItemAddRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cart 항목 추가 트랜잭션 경계.
 *
 * <p>{@link AddCartItemService}의 retry loop가 매 attempt마다 빈 경계를 넘어 호출하므로
 * 새 트랜잭션·새 persistence context로 진입한다 (self-invocation 함정 회피).
 * 트랜잭션 정책은 ADR-021(method-level @Transactional)을 따르며,
 * 영속화 호출은 ADR-022(repository.save 명시)를 따른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddCartItemProcessor {

	private final CartItemRepository cartItemRepository;

	@Transactional
	public CartItemAddedView execute(Long memberId, CartItemAddRequest request) {
		Long productId = request.getProductId();
		int quantity = request.getQuantity();

		CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.map(existing -> {
				existing.increaseQuantity(quantity);
				return cartItemRepository.save(existing);
			})
			.orElseGet(() -> cartItemRepository.save(CartItem.create(memberId, productId, quantity)));

		log.info("장바구니 항목 추가 memberId={} productId={} quantity={}",
			memberId, productId, cartItem.getQuantity());

		return CartItemAddedView.from(cartItem);
	}
}
