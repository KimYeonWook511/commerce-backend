package com.commerce.payment.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.security.annotation.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.payment.application.PaymentReadyService;
import com.commerce.payment.application.command.PaymentReadyCommand;
import com.commerce.payment.application.result.PaymentReadyResult;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.presentation.request.PaymentReadyRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

	private final PaymentReadyService paymentReadyService;

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
		PaymentReadyResult result = paymentReadyService.readyPayment(command);
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
