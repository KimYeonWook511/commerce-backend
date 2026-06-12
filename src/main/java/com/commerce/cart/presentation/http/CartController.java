package com.commerce.cart.presentation.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.cart.application.AddCartItemService;
import com.commerce.cart.application.GetMyCartService;
import com.commerce.cart.application.RemoveCartItemService;
import com.commerce.cart.application.UpdateCartItemQuantityService;
import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.application.result.CartResult;
import com.commerce.cart.presentation.http.request.CartItemAddRequest;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;
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
	private final UpdateCartItemQuantityService updateCartItemQuantityService;
	private final RemoveCartItemService removeCartItemService;

	@PostMapping("/items")
	public ResponseEntity<ApiResponse<CartItemSummaryResult>> addCartItem(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody CartItemAddRequest request
	) {
		CartItemSummaryResult result = addCartItemService.add(memberId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<CartResult>> getMyCart(
		@AuthenticatedMemberId Long memberId
	) {
		CartResult result = getMyCartService.get(memberId);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}

	@PatchMapping("/items/{productId}")
	public ResponseEntity<ApiResponse<CartItemSummaryResult>> updateCartItemQuantity(
		@AuthenticatedMemberId Long memberId,
		@PathVariable Long productId,
		@Valid @RequestBody CartItemUpdateRequest request
	) {
		CartItemSummaryResult result = updateCartItemQuantityService.update(memberId, productId, request);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}

	@DeleteMapping("/items/{productId}")
	public ResponseEntity<ApiResponse<Void>> removeCartItem(
		@AuthenticatedMemberId Long memberId,
		@PathVariable Long productId
	) {
		removeCartItemService.remove(memberId, productId);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(null));
	}
}
