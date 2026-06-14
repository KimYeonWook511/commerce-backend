package com.commerce.cart.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.cart.application.dto.CartItemResult;
import com.commerce.cart.application.dto.CartResult;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetMyCartService {

	private final CartItemRepository cartItemRepository;
	private final ProductRepository productRepository;

	@Transactional(readOnly = true)
	public CartResult get(Long memberId) {
		List<CartItem> cartItems = cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId);
		if (cartItems.isEmpty()) {
			return CartResult.builder()
				.items(List.of())
				.totalAmount(0)
				.build();
		}

		List<Long> productIds = cartItems.stream()
			.map(CartItem::getProductId)
			.toList();
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		List<CartItemResult> items = new ArrayList<>();
		for (CartItem cartItem : cartItems) {
			Product product = productsById.get(cartItem.getProductId());
			if (product == null) {
				log.warn("장바구니 상품 누락 memberId={} productId={}", memberId, cartItem.getProductId());
				continue;
			}
			items.add(toCartItemResult(cartItem, product));
		}

		int totalAmount = items.stream()
			.filter(item -> !item.isUnavailable())
			.mapToInt(CartItemResult::getLineAmount)
			.sum();

		return CartResult.builder()
			.items(items)
			.totalAmount(totalAmount)
			.build();
	}

	private CartItemResult toCartItemResult(CartItem cartItem, Product product) {
		boolean unavailable = product.getStatus() == ProductStatus.STOPPED || product.getDeletedAt() != null;
		int lineAmount = product.getPrice() * cartItem.getQuantity();
		return CartItemResult.builder()
			.productId(product.getId())
			.name(product.getName())
			.price(product.getPrice())
			.imageUrl(product.getImageUrl())
			.quantity(cartItem.getQuantity())
			.lineAmount(lineAmount)
			.unavailable(unavailable)
			.build();
	}
}
