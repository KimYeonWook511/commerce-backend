package com.commerce.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import com.commerce.auth.resolver.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.payment.controller.request.PaymentReadyRequest;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.service.NaverPayService;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
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
	private final NaverPayService naverPayService;

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

	/**
	 * 	Get Method인데 서버의 자원이 변경됨.. 이게 맞는 것인가 고민해보자
	 */
	@GetMapping("/naverpay/return")
	public ResponseEntity<Void> approveNaverPay(
		@RequestParam String resultCode,
		@RequestParam String paymentId,
		@RequestParam(required = false) String reserveId
	) {
		if (!"Success".equalsIgnoreCase(resultCode)) {
			return ResponseEntity.status(HttpStatus.FOUND)
				.header("Location", "/orders/payment/fail")
				.build();
		}

		try {
			NaverPayApproveResult result = naverPayService.approve(paymentId);
			return ResponseEntity.status(HttpStatus.FOUND)
				.header("Location", "/orders/" + result.getOrderId() + "/payment/success")
				.build();
		} catch (PaymentException ex) {
			return ResponseEntity.status(HttpStatus.FOUND)
				.header("Location", "/orders/payment/fail") // 실패했을때 api는 어떻게 할것인가
				.build();
		}
	}

	private PaymentProvider parseProvider(String provider) {
		try {
			return PaymentProvider.valueOf(provider);
		} catch (IllegalArgumentException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_NOT_SUPPORTED);
		}
	}
}
