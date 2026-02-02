package com.commerce.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.auth.resolver.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.payment.controller.request.PaymentReadyRequest;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.service.PaymentService;
import com.commerce.payment.service.request.PaymentReadyServiceRequest;
import com.commerce.payment.service.response.PaymentReadyResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping("/ready")
	public ResponseEntity<ApiResponse<PaymentReadyResponse>> readyPayment(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody PaymentReadyRequest request
	) {
		PaymentReadyServiceRequest serviceRequest = PaymentReadyServiceRequest.builder()
			.memberId(memberId)
			.orderId(request.getOrderId())
			.provider(parseProvider(request.getProvider()))
			.build();
		PaymentReadyResponse response = paymentService.readyPayment(serviceRequest);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(response));
	}

	private PaymentProvider parseProvider(String provider) {
		try {
			return PaymentProvider.valueOf(provider);
		} catch (IllegalArgumentException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_NOT_SUPPORTED);
		}
	}
}
