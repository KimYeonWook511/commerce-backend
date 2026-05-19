package com.commerce.payment.naverpay.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.commerce.common.exception.CustomException;
import com.commerce.order.application.OrderQueryService;
import com.commerce.order.domain.Order;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.application.PaymentAttemptService;
import com.commerce.payment.naverpay.application.result.NaverPayApproveResponse;
import com.commerce.payment.naverpay.application.result.NaverPayApproveStatus;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NaverPayApprovalService {

	private final NaverPayGateway naverPayGateway;
	private final OrderQueryService orderQueryService;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentAttemptService paymentAttemptService;

	public NaverPayApproveResponse approve(Long memberId, String merchantPayKey, String paymentId) {
		Order order = orderQueryService.getOrderByMerchantPayKeyAndMemberId(merchantPayKey, memberId);

		Payment existingPayment = paymentApprovalService.findPaymentByMerchantPayKey(merchantPayKey).orElse(null);
		if (existingPayment != null) {
			return toResponse(existingPayment);
		}

		PaymentAttempt attempt = paymentAttemptService.getOrCreateApproveAttempt(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			order.getTotalPrice()
		);
		return processApproveAttempt(attempt);
	}

	private NaverPayApproveResponse processApproveAttempt(PaymentAttempt attempt) {
		return switch (attempt.getStatus()) {
			case REQUESTED -> processApproveRequest(attempt);
			case SUCCEEDED -> processSucceededApproveAttempt(attempt);
			case FAILED -> throw new PaymentException(toPaymentErrorCode(attempt.getFailCode()));
			default -> throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		};
	}

	private NaverPayApproveResponse processApproveRequest(PaymentAttempt attempt) {
		NaverPayApproveResult result = naverPayGateway.approve(attempt.getPaymentId());

		return switch (result.getStatus()) {
			case PROCESSING -> toResponse(attempt.getPaymentId(), NaverPayApproveStatus.PROCESSING);
			case ALREADY_COMPLETE -> processAlreadyComplete(attempt);
			case FAILED -> {
				failApprove(attempt, result.getFailCode(), result.getFailDetail());
				throw new PaymentException(result.getErrorCode());
			}
			case SUCCESS ->
				completeVerifiedApproval(attempt, result.getMerchantPayKey(), result.getTotalPayAmount());
		};
	}

	private NaverPayApproveResponse processSucceededApproveAttempt(PaymentAttempt attempt) {
		Payment payment = paymentApprovalService.findPaymentByMerchantPayKey(attempt.getMerchantPayKey())
			.orElse(null);
		if (payment != null) {
			return toResponse(payment);
		}

		return processAlreadyComplete(attempt);
	}

	private NaverPayApproveResponse processAlreadyComplete(PaymentAttempt attempt) {
		NaverPayHistoryResult result = naverPayGateway.getApprovalHistory(attempt.getPaymentId());

		if (result.getStatus() == NaverPayHistoryResult.Status.FAILED) {
			throw new PaymentException(result.getErrorCode());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.APPROVED) {
			if (!attempt.getMerchantPayKey().equals(result.getMerchantPayKey())) {
				failApprove(attempt, PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치");
				throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
			}
			return completeVerifiedApproval(attempt, result.getMerchantPayKey(), result.getTotalPayAmount());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.CANCELED) {
			failApprove(attempt, PaymentAttemptFailCode.ALREADY_CANCELED, "이미 취소된 결제");
			throw new PaymentException(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
		}

		throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
	}

	private NaverPayApproveResponse completeVerifiedApproval(
		PaymentAttempt attempt,
		String responseMerchantPayKey,
		int responseTotalAmount
	) {
		try {
			validateApprovedMerchantPayKeyOrThrow(attempt, responseMerchantPayKey);
			validateApprovedAmountOrThrow(attempt, responseTotalAmount);

			Payment completed = paymentApprovalService.completeApprovedPayment(
				attempt.getMerchantPayKey(),
				attempt.getProvider(),
				attempt.getPaymentId(),
				LocalDateTime.now()
			);
			return toResponse(completed);
		} catch (PaymentException ex) {
			switch ((PaymentErrorCode)ex.getErrorCode()) {
				case PAYMENT_MERCHANT_KEY_MISMATCH ->
					failApprove(attempt, PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치");
				case PAYMENT_AMOUNT_MISMATCH ->
					failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.AMOUNT_MISMATCH,
						String.format("attemptAmount=%d, responseTotalAmount=%d", attempt.getAmount(),
							responseTotalAmount), responseTotalAmount, "승인 금액 불일치");
				case PAYMENT_DUPLICATE ->
					failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.DUPLICATE_PAYMENT,
						ex.getMessage(), attempt.getAmount(), "이미 다른 결제가 완료된 주문으로 인한 취소");
				default -> failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
					ex.getMessage(), attempt.getAmount(), "결제 완료 반영 실패로 인한 취소");
			}
			throw ex;
		} catch (CustomException ex) {
			failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
				ex.getMessage(), attempt.getAmount(), "결제 완료 반영 실패로 인한 취소");
			throw ex;
		} catch (Exception ex) {
			log.error(
				"NaverPay approve complete failed by unexpected error: merchantPayKey={}, paymentId={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				ex
			);
			failApproveAndCancelApprovedPayment(attempt, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
				"결제 완료 반영 중 예상치 못한 오류", attempt.getAmount(), "결제 완료 반영 실패로 인한 취소");
			throw ex;
		}
	}

	private void validateApprovedMerchantPayKeyOrThrow(PaymentAttempt attempt, String responseMerchantPayKey) {
		if (!attempt.getMerchantPayKey().equals(responseMerchantPayKey)) {
			log.warn(
				"NaverPay merchantPayKey mismatch: requestedMerchantPayKey={}, responseMerchantPayKey={}",
				attempt.getMerchantPayKey(),
				responseMerchantPayKey
			);
			throw new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
		}
	}

	private void validateApprovedAmountOrThrow(PaymentAttempt attempt, int responseTotalAmount) {
		if (attempt.getAmount() != responseTotalAmount) {
			log.warn(
				"NaverPay approve amount mismatch: merchantPayKey={}, paymentId={}, attemptAmount={}, responseTotalAmount={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				attempt.getAmount(),
				responseTotalAmount
			);
			throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}
	}

	private void failApproveAndCancelApprovedPayment(
		PaymentAttempt approveAttempt,
		PaymentAttemptFailCode failCode,
		String failDetail,
		int cancelAmount,
		String cancelReason
	) {
		try {
			failApprove(approveAttempt, failCode, failDetail);
		} catch (PaymentException markEx) {
			// PaymentException만 잡는 이유: 이 try-catch는 새로 추가된 도메인 mark 검증
			// (PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED 등)이 throw하는 경우만 처리하기 위해
			// 추가됐다. DB 장애 등 다른 예외는 여기서 삼키지 않고 그대로 전파한다.
			log.warn(
				"Approve attempt mark failed during compensation, proceeding to PG cancel: merchantPayKey={}, paymentId={}, errorCode={}",
				approveAttempt.getMerchantPayKey(),
				approveAttempt.getPaymentId(),
				markEx.getErrorCode(),
				markEx
			);
			// return 없음 — PG cancel은 무조건 시도 (외부 정합성 보존)
		}

		PaymentAttempt cancelAttempt = paymentAttemptService.getOrCreateCancelAttempt(
			approveAttempt.getMerchantPayKey(),
			approveAttempt.getProvider(),
			approveAttempt.getPaymentId(),
			cancelAmount
		);

		if (cancelAttempt.getStatus() != PaymentAttemptStatus.REQUESTED) {
			return;
		}

		try {
			processCancelRequest(cancelAttempt, cancelReason);
		} catch (PaymentException ex) {
			log.warn(
				"Approved payment cancel failed: merchantPayKey={}, paymentId={}, cancelReason={}, errorCode={}",
				cancelAttempt.getMerchantPayKey(),
				cancelAttempt.getPaymentId(),
				cancelReason,
				ex.getErrorCode()
			);
		}
	}

	private void processCancelRequest(PaymentAttempt attempt, String cancelReason) {
		NaverPayCancelResult result = naverPayGateway.cancel(
			attempt.getPaymentId(),
			attempt.getAmount(),
			cancelReason
		);

		switch (result.getStatus()) {
			case PROCESSING -> {}
			case SUCCESS, ALREADY_CANCELED -> succeedCancel(attempt);
			case FAILED -> markCancelFailed(attempt, result.getFailCode(), result.getFailDetail());
		}
	}

	private NaverPayApproveResponse toResponse(String paymentId, NaverPayApproveStatus status) {
		return NaverPayApproveResponse.builder()
			.pgPaymentId(paymentId)
			.status(status)
			.build();
	}

	private NaverPayApproveResponse toResponse(Payment payment) {
		return NaverPayApproveResponse.builder()
			.pgPaymentId(payment.getPgPaymentId())
			.status(toApproveResponseStatus(payment.getStatus()))
			.build();
	}

	private NaverPayApproveStatus toApproveResponseStatus(PaymentStatus status) {
		return switch (status) {
			case COMPLETED -> NaverPayApproveStatus.SUCCESS;
			case CANCELED -> throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		};
	}

	private PaymentErrorCode toPaymentErrorCode(PaymentAttemptFailCode failCode) {
		return switch (failCode) {
			case TIME_EXPIRED -> PaymentErrorCode.PAYMENT_TIME_EXPIRED;
			case ALREADY_CANCELED -> PaymentErrorCode.PAYMENT_ALREADY_CANCELED;
			case INVALID_PAYMENT_ID -> PaymentErrorCode.PAYMENT_NOT_FOUND;
			case INVALID_MERCHANT -> PaymentErrorCode.PAYMENT_INVALID_MERCHANT;
			case OWNER_AUTH_FAILED -> PaymentErrorCode.PAYMENT_OWNER_AUTH_FAILED;
			case NOT_ENOUGH_ACCOUNT_BALANCE -> PaymentErrorCode.PAYMENT_NOT_ENOUGH_ACCOUNT_BALANCE;
			case MERCHANT_PAY_KEY_MISMATCH -> PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH;
			case AMOUNT_MISMATCH -> PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH;
			case DUPLICATE_PAYMENT -> PaymentErrorCode.PAYMENT_DUPLICATE;
			case PG_REQUEST_REJECTED -> PaymentErrorCode.PAYMENT_PG_REQUEST_REJECTED;
			case PG_MAINTENANCE -> PaymentErrorCode.PAYMENT_PG_MAINTENANCE;
			case PG_NETWORK_ERROR -> PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR;
			case PG_SERVER_ERROR -> PaymentErrorCode.PAYMENT_PG_SERVER_ERROR;
			case PG_INVALID_RESPONSE -> PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE;
			case APPROVE_PROCESS_FAILED -> PaymentErrorCode.PAYMENT_APPROVE_FAILED;
			case CANCEL_PROCESS_FAILED -> PaymentErrorCode.PAYMENT_CANCEL_FAILED;
		};
	}

	private void failApprove(PaymentAttempt attempt, PaymentAttemptFailCode failCode, String failDetail) {
		paymentAttemptService.failApproveAttempt(
			attempt.getMerchantPayKey(),
			attempt.getProvider(),
			attempt.getPaymentId(),
			failCode,
			failDetail,
			LocalDateTime.now()
		);
	}

	private void succeedCancel(PaymentAttempt attempt) {
		paymentAttemptService.succeedCancelAttempt(
			attempt.getMerchantPayKey(),
			attempt.getProvider(),
			attempt.getPaymentId(),
			LocalDateTime.now()
		);
	}

	private void markCancelFailed(PaymentAttempt attempt, PaymentAttemptFailCode failCode, String failDetail) {
		paymentAttemptService.failCancelAttempt(
			attempt.getMerchantPayKey(),
			attempt.getProvider(),
			attempt.getPaymentId(),
			failCode,
			failDetail,
			LocalDateTime.now()
		);
	}
}
