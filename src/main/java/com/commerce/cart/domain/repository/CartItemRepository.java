package com.commerce.cart.domain.repository;

import java.util.List;
import java.util.Optional;

import com.commerce.cart.domain.CartItem;

public interface CartItemRepository {

	CartItem save(CartItem cartItem);

	Optional<CartItem> findByMemberIdAndProductId(Long memberId, Long productId);

	List<CartItem> findAllByMemberId(Long memberId);

	void deleteByMemberIdAndProductId(Long memberId, Long productId);

	void deleteByMemberIdAndProductIdIn(Long memberId, List<Long> productIds);
}
