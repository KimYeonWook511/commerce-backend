package com.commerce.payment.naverpay.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.commerce.security.annotation.AuthenticatedMemberId;
import com.commerce.common.ApiResponse;
import com.commerce.payment.naverpay.controller.request.NaverPayApproveRequest;
import com.commerce.payment.naverpay.service.NaverPayService;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments/naverpay")
public class NaverPayController {

	private final NaverPayService naverPayService;

	@GetMapping("/return")
	public void returnFromNaverPay(
		@RequestParam String merchantPayKey,
		@RequestParam String resultCode,
		@RequestParam(required = false) String resultMessage,
		@RequestParam(required = false) String paymentId,
		@RequestParam(required = false) String reserveId
	) {
		// 로그를 남길 것인가??
	}

	@PostMapping("/approve")
	public ResponseEntity<ApiResponse<NaverPayApproveResult>> approveNaverPay(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody NaverPayApproveRequest request
	) {
		NaverPayApproveResult result = naverPayService.approve(
			memberId, request.getMerchantPayKey(), request.getPaymentId());
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}

}
