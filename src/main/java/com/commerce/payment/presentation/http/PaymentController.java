package com.commerce.payment.presentation.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.common.security.annotation.AuthenticatedMemberId;
import com.commerce.payment.application.dto.StartPaymentCommand;
import com.commerce.payment.application.dto.StartPaymentResult;
import com.commerce.payment.application.usecase.StartPaymentUseCase;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.presentation.http.request.StartPaymentRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

	/**
	 * 멱등키 길이 상한. 넘으면 잘라 담지 않고 거절한다 — 앞부분이 같고 뒤만 다른 두 요청이 하나로 접히면
	 * 뒤 요청이 앞 요청의 결제창 값을 받아 엉뚱한 주문이 결제된다.
	 */
	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

	private final StartPaymentUseCase startPaymentUseCase;

	@PostMapping
	public ResponseEntity<ApiResponse<StartPaymentResult>> startPayment(
		@AuthenticatedMemberId Long memberId,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@Valid @RequestBody StartPaymentRequest request
	) {
		// 헤더가 없을 때도 ApiResponse 형태로 답하려고 required=false 로 받아 여기서 검증한다.
		if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		StartPaymentCommand command = new StartPaymentCommand(
			memberId,
			request.getOrderId(),
			parsePg(request.getProvider()),
			idempotencyKey
		);
		StartPaymentResult result = startPaymentUseCase.start(command);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}

	private PaymentPg parsePg(String provider) {
		try {
			return PaymentPg.valueOf(provider);
		} catch (IllegalArgumentException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_PG_NOT_SUPPORTED);
		}
	}
}
