package com.commerce.order.presentation.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.security.annotation.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.application.usecase.CancelOrderUseCase;
import com.commerce.order.application.usecase.CreateOrderUseCase;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.presentation.http.request.OrderCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/orders")
public class OrderController {

	/**
	 * 멱등키 길이 상한. 넘으면 잘라 담지 않고 거절한다 — 앞부분이 같고 뒤만 다른 두 요청이 하나로 접히면
	 * 뒤 요청이 앞 요청의 결과를 받는다.
	 */
	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

	private final CreateOrderUseCase createOrderUseCase;
	private final CancelOrderUseCase cancelOrderUseCase;

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
		OrderCreateResult result = createOrderUseCase.createOrder(command);

		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
	}

	@PostMapping("/{orderId}/cancel")
	public ResponseEntity<ApiResponse<OrderCancelResult>> cancelOrder(
		@AuthenticatedMemberId Long memberId,
		@PathVariable Long orderId,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
	) {
		// 헤더가 없을 때도 ApiResponse 형태로 답하려고 required=false 로 받아 여기서 검증한다.
		if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		OrderCancelResult result = cancelOrderUseCase.cancel(memberId, orderId, idempotencyKey);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}
}
