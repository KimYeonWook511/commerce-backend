package com.commerce.cart.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class CartItemRepositoryAdapter implements CartItemRepository {

	private final JpaCartItemRepository jpaCartItemRepository;

	@Override
	public CartItem save(CartItem cartItem) {
		return jpaCartItemRepository.save(cartItem);
	}

	@Override
	public Optional<CartItem> findByMemberIdAndProductId(Long memberId, Long productId) {
		return jpaCartItemRepository.findByMemberIdAndProductId(memberId, productId);
	}

	@Override
	public List<CartItem> findAllByMemberIdOrderByCreatedAtDesc(Long memberId) {
		return jpaCartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId);
	}

	@Override
	public void deleteByMemberIdAndProductId(Long memberId, Long productId) {
		jpaCartItemRepository.deleteByMemberIdAndProductId(memberId, productId);
	}

	@Override
	public void deleteByMemberIdAndProductIdIn(Long memberId, List<Long> productIds) {
		jpaCartItemRepository.deleteByMemberIdAndProductIdIn(memberId, productIds);
	}
}
