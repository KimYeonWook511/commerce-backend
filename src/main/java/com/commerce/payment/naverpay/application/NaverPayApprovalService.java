package com.commerce.payment.naverpay.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.commerce.common.exception.CustomException;
import com.commerce.order.application.OrderQueryService;
import com.commerce.order.domain.Order;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.application.PaymentApprovalAttemptService;
import com.commerce.payment.application.PaymentApprovalCompensationService;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.application.port.result.CancelOutcome;
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
	private final PaymentApprovalAttemptService paymentApprovalAttemptService;
	private final PaymentApprovalCompensationService paymentApprovalCompensationService;

	public NaverPayApproveResponse approve(Long memberId, String merchantPayKey, String pgPaymentId) {
		Order order = orderQueryService.getOrderByMerchantPayKeyAndMemberId(merchantPayKey, memberId);

		Payment existingPayment = paymentApprovalService.findPaymentByMerchantPayKey(merchantPayKey).orElse(null);
		if (existingPayment != null) {
			return toResponse(existingPayment);
		}

		PaymentAttempt attempt = paymentApprovalAttemptService.getOrCreate(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			pgPaymentId,
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
		NaverPayApproveResult result = naverPayGateway.approve(attempt.getPgPaymentId());

		return switch (result.getStatus()) {
			case PROCESSING -> toResponse(attempt.getPgPaymentId(), NaverPayApproveStatus.PROCESSING);
			case ALREADY_COMPLETE -> processAlreadyComplete(attempt);
			case FAILED -> {
				paymentApprovalAttemptService.failIfRequested(
					attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
					result.getFailCode(), result.getFailDetail(), LocalDateTime.now()
				);
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
		NaverPayHistoryResult result = naverPayGateway.getApprovalHistory(attempt.getPgPaymentId());

		if (result.getStatus() == NaverPayHistoryResult.Status.FAILED) {
			throw new PaymentException(result.getErrorCode());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.APPROVED) {
			if (!attempt.getMerchantPayKey().equals(result.getMerchantPayKey())) {
				paymentApprovalAttemptService.failIfRequested(
					attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
					PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치", LocalDateTime.now()
				);
				throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
			}
			return completeVerifiedApproval(attempt, result.getMerchantPayKey(), result.getTotalPayAmount());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.CANCELED) {
			paymentApprovalAttemptService.failIfRequested(
				attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
				PaymentAttemptFailCode.ALREADY_CANCELED, "이미 취소된 결제", LocalDateTime.now()
			);
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
			attempt.verifyApprovedResponse(responseMerchantPayKey, responseTotalAmount);

			Payment completed = paymentApprovalService.completeApprovedPayment(
				attempt.getMerchantPayKey(),
				attempt.getProvider(),
				attempt.getPgPaymentId(),
				LocalDateTime.now()
			);
			return toResponse(completed);
		} catch (PaymentException ex) {
			log.error(
				"NaverPay approve complete failed by payment error: merchantPayKey={}, pgPaymentId={}, responseMerchantPayKey={}, responseTotalAmount={}, errorCode={}",
				attempt.getMerchantPayKey(),
				attempt.getPgPaymentId(),
				responseMerchantPayKey,
				responseTotalAmount,
				ex.getErrorCode(),
				ex
			);
			switch ((PaymentErrorCode)ex.getErrorCode()) {
				case PAYMENT_MERCHANT_KEY_MISMATCH ->
					paymentApprovalCompensationService.compensateMerchantKeyMismatch(attempt);
				case PAYMENT_AMOUNT_MISMATCH ->
					paymentApprovalCompensationService.compensateAmountMismatch(attempt, responseTotalAmount, this::pgCancel);
				case PAYMENT_DUPLICATE ->
					paymentApprovalCompensationService.compensateDuplicatePayment(attempt, ex, this::pgCancel);
				default ->
					paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
			}
			throw ex;
		} catch (CustomException ex) {
			log.error(
				"NaverPay approve complete failed by custom error: merchantPayKey={}, pgPaymentId={}, errorCode={}",
				attempt.getMerchantPayKey(),
				attempt.getPgPaymentId(),
				ex.getErrorCode(),
				ex
			);
			paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
			throw ex;
		} catch (Exception ex) {
			log.error(
				"NaverPay approve complete failed by unexpected error: merchantPayKey={}, pgPaymentId={}",
				attempt.getMerchantPayKey(),
				attempt.getPgPaymentId(),
				ex
			);
			paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentAttemptFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
			throw ex;
		}
	}

	private CancelOutcome pgCancel(PaymentAttempt cancelAttempt, String cancelReason) {
		NaverPayCancelResult result = naverPayGateway.cancel(
			cancelAttempt.getPgPaymentId(), cancelAttempt.getAmount(), cancelReason
		);
		return switch (result.getStatus()) {
			case SUCCESS, ALREADY_CANCELED -> CancelOutcome.success();
			case PROCESSING -> CancelOutcome.processing();
			case FAILED -> CancelOutcome.failed(result.getFailCode(), result.getFailDetail());
		};
	}

	private NaverPayApproveResponse toResponse(String pgPaymentId, NaverPayApproveStatus status) {
		return NaverPayApproveResponse.builder()
			.pgPaymentId(pgPaymentId)
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
			case INVALID_PG_PAYMENT_ID -> PaymentErrorCode.PAYMENT_NOT_FOUND;
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
}
