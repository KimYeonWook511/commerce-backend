package com.commerce.payment.presentation.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.ApiResponse;
import com.commerce.payment.application.service.ReservePaymentService;
import com.commerce.payment.application.dto.ReservePaymentCommand;
import com.commerce.payment.application.dto.ReservePaymentResult;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.presentation.http.request.ReservePaymentRequest;
import com.commerce.common.security.annotation.AuthenticatedMemberId;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class ReservePaymentController {

	private final ReservePaymentService reservePaymentService;

	@PostMapping("/reserve")
	public ResponseEntity<ApiResponse<ReservePaymentResult>> reserve(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody ReservePaymentRequest request
	) {
		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(memberId)
			.orderId(request.getOrderId())
			.provider(parseProvider(request.getProvider()))
			.build();
		ReservePaymentResult result = reservePaymentService.reserve(command);
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
