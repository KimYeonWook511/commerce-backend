package com.commerce.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.commerce.security.annotation.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.payment.controller.request.PaymentReadyRequest;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.service.PaymentService;
import com.commerce.payment.service.command.PaymentReadyCommand;
import com.commerce.payment.service.result.PaymentReadyResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping("/ready")
	public ResponseEntity<ApiResponse<PaymentReadyResult>> readyPayment(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody PaymentReadyRequest request
	) {
		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(memberId)
			.orderId(request.getOrderId())
			.provider(parseProvider(request.getProvider()))
			.build();
		PaymentReadyResult result = paymentService.readyPayment(command);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}

	private PaymentProvider parseProvider(String provider) {
		try {
			return PaymentProvider.valueOf(provider);
		} catch (IllegalArgumentException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_NOT_SUPPORTED);
		}
	}
}
