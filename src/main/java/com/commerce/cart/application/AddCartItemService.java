package com.commerce.cart.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.result.CartItemAddedView;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.presentation.request.CartItemAddRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AddCartItemService {

	private final CartItemRepository cartItemRepository;

	public CartItemAddedView add(Long memberId, CartItemAddRequest request) {
		Long productId = request.getProductId();
		int quantity = request.getQuantity();

		Optional<CartItem> existing = cartItemRepository.findByMemberIdAndProductId(memberId, productId);
		CartItem cartItem = existing
			.map(item -> {
				item.increaseQuantity(quantity);
				return item;
			})
			.orElseGet(() -> cartItemRepository.save(CartItem.create(memberId, productId, quantity)));

		log.info("장바구니 항목 추가 memberId={} productId={} quantity={}",
			memberId, productId, cartItem.getQuantity());

		return CartItemAddedView.from(cartItem);
	}
}
