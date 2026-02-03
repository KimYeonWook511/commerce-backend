package com.commerce.payment.naverpay.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;
import com.commerce.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
	 */
	public NaverPayApproveResult approve(Long memberId, String merchantPayKey, String paymentId) {
		// Payment 상태 확인
		Payment payment = paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId);
		if (payment.getStatus() != PaymentStatus.PENDING) {
			return toResult(payment);
		}

		// 새로운 트랜잭션을 열어서 processing 마킹
		int updated = paymentService.markProcessing(merchantPayKey);
		if (updated == 0) {
			return toResult(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId));
		}

		try {
			// 네이버페이에 승인 요청
			NaverPayResponse<NaverPayApproveBody> response = naverPayClient.approve(paymentId);
			NaverPayApproveCode responseCode = NaverPayApproveCode.from(response.getCode());

			// AlreadyComplete, AlreadyOnGoing
			if (responseCode.isIdempotent()) {
				// 다른 사용자의 paymentId라면? -> memberId의 payment 반환
				return toResult(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId));
			}

			// 결제 실패
			if (!responseCode.isSuccess()) {
				// 다른 사용자 paymentId로 실패했다면?? -> 사실 추적 불가능한 것 같음
				return toFailResult(payment, toPaymentErrorCode(responseCode));
			}

			NaverPayApproveBody.Detail detail = getDetail(response);

			// 악의적인 공격 - 결제 금액을 다르게 함 (결제 승인 취소 하기!)
			Payment detailPayment = paymentService.getPaymentByMerchantPayKey(detail.getMerchantPayKey());
			if (detailPayment.getAmount() != detail.getTotalPayAmount()) {
				// Todo: 결제 승인 취소해야함
				return toFailResult(payment, PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			}

			// 결제 성공 처리
			Payment completed = paymentService.completePayment(
				detail.getMerchantPayKey(), detail.getPaymentId(), LocalDateTime.now());

			// 악의적인 공격 - 다른 사람의 승인 전 paymentId로 approve 요청
			if (!merchantPayKey.equals(detail.getMerchantPayKey())) {
				// 정상적인 요청이 아니라는 처리 (해커가 예측 못하게 NOT_FOUND 반환)
				log.info("NaverPay merchantPayKey mismatch: requestKey={}, responseKey={}, paymentId={}",
					merchantPayKey, detail.getMerchantPayKey(), detail.getPaymentId());
				throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
			}

			return toResult(completed);
		} catch (NaverPayException ex) {
			PaymentErrorCode errorCode = ex.isRetryable()
				? PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED
				: PaymentErrorCode.PAYMENT_APPROVAL_FAILED;
			return toFailResult(payment, errorCode);
		}
	}

	public NaverPayApproveResult failByResultCode(
		String merchantPayKey,
		String resultCode,
		String resultMessage
	) {
		Payment payment = paymentService.getPaymentByMerchantPayKey(merchantPayKey);
		if (payment.getStatus() != PaymentStatus.PENDING) {
			return toResult(payment);
		}

		int updated = paymentService.markProcessing(merchantPayKey);
		if (updated == 0) {
			return toResult(paymentService.getPaymentByMerchantPayKey(merchantPayKey));
		}

		log.info("NaverPay payment failed before approval: merchantPayKey={}, resultCode={}, resultMessage={}",
			merchantPayKey, resultCode, resultMessage);
		NaverPayResultCode enumResultCode = NaverPayResultCode.from(resultCode);
		return toFailResult(payment, toPaymentErrorCode(enumResultCode));
	}

	private NaverPayApproveResult toResult(Payment payment) {
		return NaverPayApproveResult.builder()
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

	private NaverPayApproveBody.Detail getDetail(NaverPayResponse<NaverPayApproveBody> response) {
		try {
			return response.getBody().getDetail();
		} catch (NullPointerException ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
		}
	}

	private NaverPayApproveResult toFailResult(Payment payment, PaymentErrorCode errorCode) {
		Payment failed = paymentService.failPayment(
			payment.getMerchantPayKey(), null, errorCode.getMessage());
		return toResult(failed);
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayApproveCode approveCode) {
		return switch (approveCode) {
			case TIME_EXPIRED -> PaymentErrorCode.PAYMENT_TIME_EXPIRED;
			case INVALID_MERCHANT, OWNER_AUTH_FAIL, NOT_ENOUGH_ACCOUNT_BALANCE ->
				PaymentErrorCode.PAYMENT_APPROVAL_FAILED;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING ->
				PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED;
			default -> PaymentErrorCode.PAYMENT_APPROVAL_FAILED;
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayResultCode resultCode) {
		return switch (resultCode) {
			case USER_CANCEL -> PaymentErrorCode.PAYMENT_USER_CANCELED;
			case TIME_EXPIRED -> PaymentErrorCode.PAYMENT_TIME_EXPIRED;
			case UNDER_AGE_AMOUNT_LIMIT -> PaymentErrorCode.PAYMENT_UNDERAGE_LIMIT;
			default -> PaymentErrorCode.PAYMENT_APPROVAL_FAILED;
		};
	}
}
