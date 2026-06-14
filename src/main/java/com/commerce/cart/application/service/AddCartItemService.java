package com.commerce.cart.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.domain.exception.CartErrorCode;
import com.commerce.cart.domain.exception.CartException;
import com.commerce.cart.presentation.http.request.CartItemAddRequest;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cart 항목 추가 트랜잭션 경계. 정책: ADR-021, ADR-022, cart adr 결정 6-5/8.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddCartItemService {

	private final CartItemRepository cartItemRepository;
	private final ProductRepository productRepository;

	@Transactional
	public CartItemSummaryResult execute(Long memberId, CartItemAddRequest request) {
		Long productId = request.getProductId();
		int quantity = request.getQuantity();

		validatePurchasable(productId);

		CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
			.map(existing -> {
				existing.increaseQuantity(quantity);
				return cartItemRepository.save(existing);
			})
			.orElseGet(() -> cartItemRepository.save(CartItem.create(memberId, productId, quantity)));

		log.info("장바구니 항목 추가 memberId={} productId={} quantity={}",
			memberId, productId, cartItem.getQuantity());

		return CartItemSummaryResult.from(cartItem);
	}

	private void validatePurchasable(Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_PRODUCT_NOT_FOUND));
		if (product.getDeletedAt() != null) {
			throw new CartException(CartErrorCode.CART_ITEM_PRODUCT_NOT_FOUND);
		}
		if (product.getStatus() == ProductStatus.STOPPED) {
			throw new CartException(CartErrorCode.CART_ITEM_PRODUCT_UNAVAILABLE);
		}
	}
}
