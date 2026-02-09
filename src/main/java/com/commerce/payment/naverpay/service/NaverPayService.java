package com.commerce.payment.naverpay.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentReasonCode;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequester;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
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
		Payment payment = paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId);
		if (!isPending(payment)) {
			return toResult(payment);
		}

		if (!markProcessing(merchantPayKey)) {
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
				return toFailResult(merchantPayKey, toPaymentReasonCode(responseCode));
			}

			// 결제 성공 처리
			NaverPayApproveBody.Detail detail = getDetail(response);
			return handleApproveSuccess(merchantPayKey, detail);
		} catch (NaverPayException ex) {
			return handleNaverPayException(merchantPayKey, ex);
		}
	}

	/**
	 *  결제 요청 중 실패된 처리 (결제 승인 전 단게에서 실패)
	 */
	public NaverPayApproveResult preApproveFail(
		Long memberId,
		String merchantPayKey,
		String resultCode,
		String resultMessage
	) {
		Payment payment = paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId);
		if (!isPending(payment)) {
			return toResult(payment);
		}

		if (!markProcessing(merchantPayKey)) {
			return toResult(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId));
		}

		log.info("NaverPay payment failed before approval: merchantPayKey={}, resultCode={}, resultMessage={}",
			merchantPayKey, resultCode, resultMessage);
		NaverPayResultCode enumResultCode = NaverPayResultCode.from(resultCode);
		return toFailResult(merchantPayKey, toPaymentReasonCode(enumResultCode),
			resultMessage != null ? resultMessage : enumResultCode.getDescription());
	}

	private NaverPayApproveResult handleApproveSuccess(String merchantPayKey, NaverPayApproveBody.Detail detail) {
		Payment detailPayment = paymentService.getPaymentByMerchantPayKey(detail.getMerchantPayKey());
		if (detailPayment.getAmount() != detail.getTotalPayAmount()) {
			// 악의적인 공격 - 결제 금액을 다르게 함 (결제 승인 취소)
			handleAmountMismatch(detailPayment, detail);
		}

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
	}

	private void handleAmountMismatch(Payment payment, NaverPayApproveBody.Detail detail) {
		// 결제 취소 요청 전 상태 확인
		if (!isProcessing(payment)) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}

		// 결제 취소 요청 전 상태 변경
		if (!markCancelPending(detail.getMerchantPayKey(), detail.getPaymentId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}

		// 결제 취소 요청
		NaverPayResponse<NaverPayCancelBody> cancelResponse = naverPayClient.cancel(
			NaverPayCancelRequest.builder()
				.paymentId(detail.getPaymentId())
				.cancelAmount(detail.getTotalPayAmount())
				.cancelReason(PaymentReasonCode.AMOUNT_MISMATCH.getDescription())
				.cancelRequester(NaverPayCancelRequester.CANCEL_BY_ADMIN)
				.taxScopeAmount(detail.getTotalPayAmount())
				.taxExScopeAmount(0)
				.build());
		NaverPayCancelCode cancelResponseCode = NaverPayCancelCode.from(cancelResponse.getCode());

		if (cancelResponseCode.isIdempotent()) {
			// AlreadyCanceled, AlreadyOnGoing
			throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}

		if (cancelResponseCode.isSuccess()) {
			// 취소 성공
			NaverPayCancelBody cancelBody = getBody(cancelResponse);
			paymentService.completeCancelPayment(cancelBody.getPaymentId());
		} else {
			// 취소 실패 -> 우선 로그로 남기기
			// 추후 배치로 취소 요청 하도록 하기 (CANCEL_PENDING + updateAt(+60s))
			log.warn(
				"NaverPay cancel failed: merchantPayKey={}, paymentId={}, code={}, message={}",
				detail.getMerchantPayKey(),
				detail.getPaymentId(),
				cancelResponse.getCode(),
				cancelResponse.getMessage()
			);
		}

		throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
	}

	private NaverPayApproveResult handleNaverPayException(String merchantPayKey, NaverPayException ex) {
		if (ex.isRetryable()) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED);
		}
		return toFailResult(merchantPayKey, toPaymentReasonCode(ex));
	}

	private boolean isPending(Payment payment) {
		return payment.getStatus() == PaymentStatus.PENDING;
	}

	private boolean isProcessing(Payment payment) {
		return payment.getStatus() == PaymentStatus.PROCESSING;
	}

	private boolean markProcessing(String merchantPayKey) {
		return paymentService.markProcessing(merchantPayKey) > 0;
	}

	private boolean markCancelPending(String merchantPayKey, String pgPaymentId) {
		return paymentService.markCancelPending(
			merchantPayKey,
			pgPaymentId,
			PaymentReasonCode.AMOUNT_MISMATCH,
			PaymentReasonCode.AMOUNT_MISMATCH.getDescription()) > 0;
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

	private NaverPayApproveResult toFailResult(String merchantPayKey, PaymentReasonCode reasonCode) {
		return toFailResult(merchantPayKey, reasonCode, reasonCode.getDescription());
	}

	private NaverPayApproveResult toFailResult(String merchantPayKey, PaymentReasonCode reasonCode,
		String reasonDetail) {
		Payment failed = paymentService.failPayment(merchantPayKey, reasonCode, reasonDetail);
		return toResult(failed);
	}

	private PaymentReasonCode toPaymentReasonCode(NaverPayApproveCode approveCode) {
		return switch (approveCode) {
			case TIME_EXPIRED -> PaymentReasonCode.TIME_EXPIRED;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentReasonCode.PG_MAINTENANCE;
			case INVALID_MERCHANT, OWNER_AUTH_FAIL, NOT_ENOUGH_ACCOUNT_BALANCE, FAIL ->
				PaymentReasonCode.APPROVAL_FAILED;
			default -> PaymentReasonCode.UNKNOWN;
		};
	}

	private PaymentReasonCode toPaymentReasonCode(NaverPayException ex) {
		return switch (ex.getErrorCode()) {
			case NETWORK -> PaymentReasonCode.PG_NETWORK_ERROR;
			case SERVER_ERROR -> PaymentReasonCode.PG_SERVER_ERROR;
			case CLIENT_ERROR, AUTHENTICATION, INVALID_RESPONSE -> PaymentReasonCode.APPROVAL_FAILED;
			default -> PaymentReasonCode.UNKNOWN;
		};
	}

	private PaymentReasonCode toPaymentReasonCode(NaverPayResultCode resultCode) {
		return switch (resultCode) {
			case USER_CANCEL -> PaymentReasonCode.USER_CANCELED;
			case TIME_EXPIRED -> PaymentReasonCode.TIME_EXPIRED;
			case UNDER_AGE_AMOUNT_LIMIT -> PaymentReasonCode.UNDER_AGE_LIMIT;
			case FAIL -> PaymentReasonCode.PRE_APPROVE_FAILED;
			default -> PaymentReasonCode.UNKNOWN;
		};
	}

	private NaverPayApproveBody.Detail getDetail(NaverPayResponse<NaverPayApproveBody> response) {
		try {
			return response.getBody().getDetail();
		} catch (NullPointerException ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
		}
	}

	private NaverPayCancelBody getBody(NaverPayResponse<NaverPayCancelBody> response) {
		try {
			return response.getBody();
		} catch (NullPointerException ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
		}
	}
}
