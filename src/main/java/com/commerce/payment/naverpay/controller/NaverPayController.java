package com.commerce.payment.naverpay.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.service.NaverPayService;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments/naverpay")
public class NaverPayController {

	private final NaverPayService naverPayService;

	/**
	 * 	Get Method인데 서버의 자원이 변경됨.. 이게 맞는 것인가 고민해보자
	 */
	@GetMapping("/return/{merchantPayKey}")
	public ResponseEntity<Void> approveNaverPay(
		@PathVariable String merchantPayKey,
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
			NaverPayApproveResult result = naverPayService.approve(merchantPayKey, paymentId);
			// 처리 중인 결제 (중복 요청 방지)
			if (result.getStatus() == NaverPayApproveStatus.PROCESSING) {
				return ResponseEntity.status(HttpStatus.FOUND)
					.header("Location", "/orders/" + result.getOrderId() + "/payment/processing")
					.build();
			}

			// 실패한 결제
			if (result.getStatus() == NaverPayApproveStatus.FAIL) {
				return ResponseEntity.status(HttpStatus.FOUND)
					.header("Location", "/orders/payment/fail")
					.build();
			}

			// 결제 성공
			return ResponseEntity.status(HttpStatus.FOUND)
				.header("Location", "/orders/" + result.getOrderId() + "/payment/success")
				.build();
		} catch (PaymentException ex) {
			// GlobalExceptionHandler에서 처리할 지..
			return ResponseEntity.status(HttpStatus.FOUND)
				.header("Location", "/orders/payment/fail")
				.build();
		}
	}
}
