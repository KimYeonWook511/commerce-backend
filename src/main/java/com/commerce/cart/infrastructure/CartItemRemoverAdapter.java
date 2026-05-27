package com.commerce.cart.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.order.application.port.CartItemRemover;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CartItemRemoverAdapter implements CartItemRemover {

	private final CartItemRepository cartItemRepository;

	@Override
	public void removeByMemberAndProducts(Long memberId, List<Long> productIds) {
		if (productIds.isEmpty()) {
			return;
		}
		cartItemRepository.deleteByMemberIdAndProductIdIn(memberId, productIds);
	}
}
