package com.commerce.cart.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.domain.repository.CartItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RemoveCartItemService {

	private final CartItemRepository cartItemRepository;

	public void remove(Long memberId, Long productId) {
		cartItemRepository.deleteByMemberIdAndProductId(memberId, productId);

		log.info("장바구니 항목 삭제 memberId={} productId={}", memberId, productId);
	}
}
