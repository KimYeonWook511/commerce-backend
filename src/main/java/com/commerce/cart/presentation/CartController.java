package com.commerce.cart.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.cart.application.AddCartItemService;
import com.commerce.cart.application.GetMyCartService;
import com.commerce.cart.application.result.CartItemAddedView;
import com.commerce.cart.application.result.CartView;
import com.commerce.cart.presentation.request.CartItemAddRequest;
import com.commerce.common.ApiResponse;
import com.commerce.security.annotation.AuthenticatedMemberId;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/cart")
public class CartController {

	private final AddCartItemService addCartItemService;
	private final GetMyCartService getMyCartService;

	@PostMapping("/items")
	public ResponseEntity<ApiResponse<CartItemAddedView>> addCartItem(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody CartItemAddRequest request
	) {
		CartItemAddedView result = addCartItemService.add(memberId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<CartView>> getMyCart(
		@AuthenticatedMemberId Long memberId
	) {
		CartView result = getMyCartService.get(memberId);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}
}
