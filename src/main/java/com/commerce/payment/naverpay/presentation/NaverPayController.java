package com.commerce.payment.naverpay.presentation;

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
import com.commerce.payment.naverpay.application.NaverPayApprovalService;
import com.commerce.payment.naverpay.application.result.NaverPayApproveResponse;
import com.commerce.payment.naverpay.presentation.request.NaverPayApproveRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments/naverpay")
public class NaverPayController {

	private final NaverPayApprovalService naverPayApprovalService;

	@GetMapping("/return")
	public void returnFromNaverPay(
		@RequestParam String merchantPayKey,
		@RequestParam String resultCode,
		@RequestParam(required = false) String resultMessage,
		@RequestParam(name = "paymentId", required = false) String pgPaymentId,
		@RequestParam(required = false) String reserveId
	) {
	}

	@PostMapping("/approve")
	public ResponseEntity<ApiResponse<NaverPayApproveResponse>> approveNaverPay(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody NaverPayApproveRequest request
	) {
		NaverPayApproveResponse response = naverPayApprovalService.approve(
			memberId, request.getMerchantPayKey(), request.getPaymentId());
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(response));
	}

}
