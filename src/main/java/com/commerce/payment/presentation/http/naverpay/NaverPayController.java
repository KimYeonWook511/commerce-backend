package com.commerce.payment.presentation.http.naverpay;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.ApiResponse;
import com.commerce.common.security.annotation.AuthenticatedMemberId;
import com.commerce.payment.application.dto.ApprovalResult;
import com.commerce.payment.application.usecase.RequestApprovalUseCase;
import com.commerce.payment.presentation.http.naverpay.request.NaverPayApproveRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 결제창에서 돌아오는 진입점. 경로가 결제사별인 것은 결제사가 우리에게 돌려보내는 규격이라 우리가 정할
 * 수 없고, 진입점이 갈리면 그것을 받는 클래스도 갈린다. 결제사 전용 타입에 대한 의존은 여기 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/payments/naverpay")
public class NaverPayController {

	private final RequestApprovalUseCase requestApprovalUseCase;

	/** 결제창이 회원을 돌려보내는 주소. 승인은 클라이언트가 아래 요청으로 따로 건다 */
	@GetMapping("/return")
	public void returnFromNaverPay(
		@RequestParam String merchantPayKey,
		@RequestParam String resultCode,
		@RequestParam(required = false) String resultMessage,
		@RequestParam(name = "paymentId", required = false) String pgPaymentId
	) {
	}

	@PostMapping("/approve")
	public ResponseEntity<ApiResponse<ApprovalResult>> approve(
		@AuthenticatedMemberId Long memberId,
		@Valid @RequestBody NaverPayApproveRequest request
	) {
		ApprovalResult result = requestApprovalUseCase.approve(
			memberId, request.getMerchantPayKey(), request.getPaymentId());
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(result));
	}
}
