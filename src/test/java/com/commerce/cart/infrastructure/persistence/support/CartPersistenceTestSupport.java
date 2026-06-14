package com.commerce.cart.infrastructure.persistence.support;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.cart.domain.CartItem;
import com.commerce.cart.infrastructure.persistence.JpaCartItemRepository;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class CartPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaCartItemRepository cartItemRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.CART;
	}

	@Override
	public void deleteAllInBatch() {
		cartItemRepository.deleteAllInBatch();
	}

	public CartItem save(CartItem cartItem) {
		return cartItemRepository.save(cartItem);
	}

	public CartItem saveAndFlush(CartItem cartItem) {
		return cartItemRepository.saveAndFlush(cartItem);
	}

	public Optional<CartItem> findById(Long id) {
		return cartItemRepository.findById(id);
	}

	public List<CartItem> findAllByMemberIdOrderByCreatedAtDesc(Long memberId) {
		return cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId);
	}

	public long count() {
		return cartItemRepository.count();
	}
}
