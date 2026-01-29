package com.commerce.payment.naverpay.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.NaverPayApproveResponse;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NaverPayService {

	private final NaverPayClient naverPayClient;
	private final PaymentRepository paymentRepository;

	/**
	 * 	문제점
	 * 	1. 중복 요청(멱등성)에 대해 고민해 보기
	 * 	2. 사용자가 악의적으로 요청을 만들어서 계속 보낼 수 있음 (보안)
	 * 		- 랜덤하게 paymentId를 악의적으로 보내서 결제 승인 요청을 해버릴 수 있음
	 * 		- 우선 returnUrl에서 merchantPayKey를 넘겨주도록 한다면 외부API 요청 전에 우리 DB에서 확인 가능함 (외부 API 낭비 방지)
	 * 		- redirect를 하는 것이기 때문에 GET ~~/naverpay/return 에는 헤더가 없음 (accessToken 사용 불가)
	 * 		- 생각해 보면 memberId를 받으면서 해당 결제가 memberId가 한것이 맞는지를 확인할 필요는 없는것 같음
	 * 		- 이유는 paymentId가 생성되었다는 것은 A라는 사용자가 결제를 정상적으로 진행한 것이고, 당연히 우리 서버는 바로 이것을 승인처리 해야함
	 * 		- 즉 결제 승인 전 paymentId에 대해서는 승인처리가 되는 것이 맞음
	 */
	@Transactional
	public NaverPayApproveResult approve(String paymentId) {
		// 네이버 페이에 단건 결제 승인 요청
		NaverPayApproveResponse response = naverPayClient.approve(paymentId);
		if (response == null || response.getBody() == null || response.getBody().getDetail() == null) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
		}

		NaverPayApproveResponse.Detail detail = response.getBody().getDetail();
		if (!"Success".equalsIgnoreCase(response.getCode()) || !"SUCCESS".equalsIgnoreCase(detail.getAdmissionState())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
		}

		Payment payment = paymentRepository.findByMerchantPayKey(detail.getMerchantPayKey())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (payment.getAmount() != detail.getTotalPayAmount()) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
		}

		payment.assignPgPaymentId(detail.getPaymentId());
		payment.complete(LocalDateTime.now());

		return NaverPayApproveResult.builder()
			.orderId(payment.getOrder().getId())
			.pgPaymentId(payment.getPgPaymentId())
			.status(payment.getStatus())
			.build();
	}
}
