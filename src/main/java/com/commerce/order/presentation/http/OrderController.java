package com.commerce.order.presentation.http;

import java.util.List;

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
import com.commerce.order.domain.OrderCancelLine;
import com.commerce.order.presentation.http.request.OrderCancelRequest;
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
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@Valid @RequestBody(required = false) OrderCancelRequest request
	) {
		// 헤더가 없을 때도 ApiResponse 형태로 답하려고 required=false 로 받아 여기서 검증한다.
		if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		OrderCancelResult result = cancelOrderUseCase.cancel(
			memberId, orderId, idempotencyKey, cancelLines(request));
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}

	/**
	 * 본문이 없거나 품목 목록이 비면 빈 목록으로 넘긴다 — 남은 수량 전부를 취소한다는 뜻이고, 그 해석은
	 * 주문이 한다.
	 *
	 * <p>같은 주문 상품이 여러 줄로 오면 합치지 않고 거절한다. 취소는 되돌릴 수 없어 모호한 의도를 서버가
	 * 해석해 실행하지 않는다. 이 거절을 요청 경계에만 두어, 도메인과 양쪽에서 걸릴 때 응답이 갈리는 것을
	 * 막는다.
	 */
	private static List<OrderCancelLine> cancelLines(OrderCancelRequest request) {
		if (request == null || request.getItems() == null) {
			return List.of();
		}

		List<OrderCancelLine> lines = request.getItems().stream()
			.map(item -> new OrderCancelLine(item.getOrderItemId(), item.getQuantity()))
			.toList();
		long distinctItemCount = lines.stream().map(OrderCancelLine::orderItemId).distinct().count();
		if (distinctItemCount != lines.size()) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
		return lines;
	}
}
