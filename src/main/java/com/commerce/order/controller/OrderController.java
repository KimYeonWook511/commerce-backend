package com.commerce.order.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.auth.resolver.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.controller.request.OrderCreateItemRequest;
import com.commerce.order.controller.request.OrderCreateRequest;
import com.commerce.order.service.OrderService;
import com.commerce.order.service.request.OrderCreateItem;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.order.service.response.OrderCreateResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/orders")
public class OrderController {

	private final OrderService orderService;

	@PostMapping
	public ResponseEntity<ApiResponse<OrderCreateResponse>> createOrder(
		@AuthenticatedMemberId Long memberId,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@Valid @RequestBody OrderCreateRequest request
	) {
		// ApiResponse형태로 보내기 위해 required=false로함 -> GlobalExceptionHandler에서 처리해도 됨
		if (!StringUtils.hasText(idempotencyKey)) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		OrderCreateServiceRequest serviceRequest = OrderCreateServiceRequest.builder()
			.memberId(memberId)
			.idempotencyKey(idempotencyKey)
			.items(request.toServiceRequestItems())
			.build();
		OrderCreateResponse response = orderService.createOrder(serviceRequest);

		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
	}
}
