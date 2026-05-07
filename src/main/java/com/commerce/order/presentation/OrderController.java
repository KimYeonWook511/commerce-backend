package com.commerce.order.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.security.annotation.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.application.OrderCancelService;
import com.commerce.order.application.OrderCreateService;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.result.OrderCancelResult;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.presentation.request.OrderCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/orders")
public class OrderController {

	private final OrderCreateService orderCreateService;
	private final OrderCancelService orderCancelService;

	@PostMapping
	public ResponseEntity<ApiResponse<OrderCreateResult>> createOrder(
		@AuthenticatedMemberId Long memberId,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@Valid @RequestBody OrderCreateRequest request
	) {
		// ApiResponse형태로 보내기 위해 required=false로함 -> GlobalExceptionHandler에서 처리해도 됨
		if (!StringUtils.hasText(idempotencyKey)) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(memberId)
			.idempotencyKey(idempotencyKey)
			.items(request.getItems().stream()
				.map(item -> OrderCreateItem.builder()
					.productId(item.getProductId())
					.quantity(item.getQuantity())
					.build())
				.toList())
			.build();
		OrderCreateResult result = orderCreateService.createOrder(command);

		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
	}

	@PostMapping("/{orderId}/cancel")
	public ResponseEntity<ApiResponse<OrderCancelResult>> cancelOrder(
		@AuthenticatedMemberId Long memberId,
		@PathVariable Long orderId
	) {
		OrderCancelResult result = orderCancelService.cancelOrder(memberId, orderId);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}
}
