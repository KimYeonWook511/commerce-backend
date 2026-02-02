package com.commerce.payment.naverpay.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.NaverPayApproveResponse;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;
import com.commerce.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NaverPayService {

	private final NaverPayClient naverPayClient;
	private final PaymentService paymentService;

	/**
	 * 	문제점
	 * 	1. 중복 요청(멱등성)에 대해 고민해 보기
	 * 		- 멱등키는 사용할 수 없음 (굳이 한다면 paymentId로 해야함)
	 * 		- 현재는 DB에서 payemnt.status를 확인하여 멱등하도록 구현함
	 * 		- 단점으로는 DB에 부하가 갈 수 있음
	 * 		- 장점으로는 redis를 굳이 안 둬도 처리할 수 있는 형태 + 외부 API 낭비 방지함
	 * 	2. 사용자가 악의적으로 요청을 만들어서 계속 보낼 수 있음 (보안)
	 * 		- 랜덤하게 paymentId를 악의적으로 보내서 결제 승인 요청을 해버릴 수 있음
	 * 		- 우선 returnUrl에서 merchantPayKey를 넘겨주도록 한다면 외부API 요청 전에 우리 DB에서 확인 가능함 (외부 API 낭비 방지)
	 * 		- redirect를 하는 것이기 때문에 GET ~~/naverpay/return 에는 헤더가 없음 (accessToken 사용 불가)
	 * 		- 생각해 보면 memberId를 받으면서 해당 결제가 memberId가 한것이 맞는지를 확인할 필요는 없는것 같음
	 * 		- 이유는 paymentId가 생성되었다는 것은 A라는 사용자가 결제를 정상적으로 진행한 것이고, 당연히 우리 서버는 바로 이것을 승인처리 해야함
	 * 		- 즉 결제 승인 전 paymentId에 대해서는 승인처리가 되는 것이 맞음
	 */
	public NaverPayApproveResult approve(String merchantPayKey, String paymentId) {
		// Payment 상태 확인
		Payment payment = paymentService.getPaymentByMerchantPayKey(merchantPayKey);
		if (payment.getStatus() == PaymentStatus.COMPLETED) {
			return toResult(payment);
		}
		if (payment.getStatus() == PaymentStatus.PROCESSING) {
			return toResult(payment);
		}
		if (payment.getStatus() != PaymentStatus.PENDING) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		}

		// 새로운 트랜잭션을 열어서 processing 마킹
		int updated = paymentService.markProcessing(merchantPayKey);
		if (updated == 0) {
			return toResult(paymentService.getPaymentByMerchantPayKey(merchantPayKey));
		}

		try {
			// 네이버페이에 승인 요청
			NaverPayApproveResponse response = naverPayClient.approve(paymentId);
			if (response == null || response.getBody() == null || response.getBody().getDetail() == null) {
				throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			}

			NaverPayApproveResponse.Detail detail = response.getBody().getDetail();
			if (!"Success".equalsIgnoreCase(response.getCode()) || !"SUCCESS".equalsIgnoreCase(detail.getAdmissionState())) {
				throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			}

			// 이상한 요청 (paymentId를 조작해서 보냈을 가능성)
			if (!merchantPayKey.equals(detail.getMerchantPayKey())) {
				throw new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			}

			if (payment.getAmount() != detail.getTotalPayAmount()) {
				throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			}

			Payment completed = paymentService.completePayment(
				merchantPayKey, detail.getPaymentId(), LocalDateTime.now());
			return toResult(completed);
		} catch (PaymentException ex) {
			Payment failed = paymentService.failPayment(
				merchantPayKey, null, ex.getErrorCode().getMessage());
			return toResult(failed);
		}
	}

	private NaverPayApproveResult toResult(Payment payment) {
		return NaverPayApproveResult.builder()
			.orderId(payment.getOrder().getId())
			.pgPaymentId(payment.getPgPaymentId())
			.status(toApproveStatus(payment.getStatus()))
			.build();
	}

	private NaverPayApproveStatus toApproveStatus(PaymentStatus status) {
		return switch (status) {
			case COMPLETED -> NaverPayApproveStatus.SUCCESS;
			case PROCESSING -> NaverPayApproveStatus.PROCESSING;
			case FAILED, CANCELED -> NaverPayApproveStatus.FAIL;
			default -> NaverPayApproveStatus.FAIL;
		};
	}

}
