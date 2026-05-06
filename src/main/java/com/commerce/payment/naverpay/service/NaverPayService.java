package com.commerce.payment.naverpay.service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

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
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequester;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NaverPayService {

	private final NaverPayClient naverPayClient;
	private final OrderQueryService orderQueryService;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentAttemptService paymentAttemptService;

	public NaverPayApproveResult approve(Long memberId, String merchantPayKey, String paymentId) {
		// 주문 확인
		Order order = orderQueryService.getOrderByMerchantPayKeyAndMemberId(merchantPayKey, memberId);

		// 이미 결제가 완료됐는지 확인
		Payment existingPayment = paymentApprovalService.findPaymentByMerchantPayKey(merchantPayKey).orElse(null);
		if (existingPayment != null) {
			return toResult(existingPayment);
		}

		PaymentAttempt attempt = paymentAttemptService.getOrCreateApproveAttempt(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			order.getTotalPrice()
		);
		return processApproveAttempt(attempt);
	}

	private NaverPayApproveResult processApproveAttempt(PaymentAttempt attempt) {
		return switch (attempt.getStatus()) {
			case REQUESTED -> processApproveRequest(attempt);
			case SUCCEEDED -> processSucceededApproveAttempt(attempt);
			case FAILED -> throw new PaymentException(toPaymentErrorCode(attempt.getFailCode()));
			default -> throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		};
	}

	private NaverPayApproveResult processApproveRequest(PaymentAttempt attempt) {
		NaverPayResponse<NaverPayApproveBody> response = requestApprove(attempt);

		NaverPayApproveCode responseCode = NaverPayApproveCode.from(response.getCode());
		if (!responseCode.isSuccess()) {
			log.warn(
				"NaverPay approve failed: merchantPayKey={}, paymentId={}, code={}, message={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				response.getCode(),
				responseCode.getDescription()
			);

			if (responseCode.isAlreadyOnGoing()) {
				return toResult(attempt.getPaymentId(), NaverPayApproveStatus.PROCESSING);
			}
			if (responseCode.isAlreadyComplete()) {
				return processAlreadyComplete(attempt);
			}

			// 승인 시도 실패 처리
			failApprove(attempt, toAttemptFailCode(responseCode), responseCode.getDescription());
			throw new PaymentException(toPaymentErrorCode(responseCode));
		}

		NaverPayApproveBody.Detail detail = getDetail(response);
		return completeVerifiedApproval(attempt, detail.getMerchantPayKey(), detail.getTotalPayAmount());
	}

	private NaverPayApproveResult processSucceededApproveAttempt(PaymentAttempt attempt) {
		Payment payment = paymentApprovalService.findPaymentByMerchantPayKey(attempt.getMerchantPayKey()).orElse(null);
		if (payment != null) {
			return toResult(payment);
		}

		return processAlreadyComplete(attempt);
	}

	private NaverPayApproveResult processAlreadyComplete(PaymentAttempt attempt) {
		NaverPayResponse<NaverPayHistoryBody> response = requestAllHistory(attempt);

		NaverPayHistoryCode responseCode = NaverPayHistoryCode.from(response.getCode());
		if (!responseCode.isSuccess()) {
			throw new PaymentException(toPaymentErrorCode(responseCode));
		}

		NaverPayHistoryBody.History history = getLatestHistory(response);
		if (!attempt.getMerchantPayKey().equals(history.getMerchantPayKey())) {
			failApprove(attempt, PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치");
			throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
		}

		if (history.isCompletedApproval()) {
			return completeVerifiedApproval(attempt, history.getMerchantPayKey(), history.getTotalPayAmount());
		}
		if (history.isCanceledApproval()) {
			failApprove(attempt, PaymentAttemptFailCode.ALREADY_CANCELED, "이미 취소된 결제");
			throw new PaymentException(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
		}
		throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
	}

	private NaverPayApproveResult completeVerifiedApproval(
		PaymentAttempt attempt,
		String responseMerchantPayKey,
		int responseTotalAmount
	) {
		try {
			// merchantPayKey 검증
			validateApprovedMerchantPayKeyOrThrow(attempt, responseMerchantPayKey);

			// 결제 금액 검증
			validateApprovedAmountOrThrow(attempt, responseTotalAmount);

			Payment completed = paymentApprovalService.completeApprovedPayment(
				attempt.getMerchantPayKey(),
				attempt.getProvider(),
				attempt.getPaymentId(),
				LocalDateTime.now()
			);
			return toResult(completed);
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
			// 예외 적으로 승인 취소 지연시킬 경우가 있는지?
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
		// 승인 시도 실패 처리
		failApprove(approveAttempt, failCode, failDetail);

		// 취소 시도 생성
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
		NaverPayResponse<NaverPayCancelBody> response = requestCancel(
			attempt,
			attempt.getAmount(),
			cancelReason
		);

		NaverPayCancelCode responseCode = NaverPayCancelCode.from(response.getCode());
		if (responseCode.isAlreadyOnGoing()) {
			return;
		}
		if (responseCode.isSuccess() || responseCode.isAlreadyCanceled()) {
			// AlreadyCanceled는 AlreadyComplet와 달리 검증이 필요 없음
			succeedCancel(attempt);
			return;
		}

		log.warn(
			"NaverPay cancel failed: merchantPayKey={}, paymentId={}, cancelReason={}, code={}, message={}",
			attempt.getMerchantPayKey(),
			attempt.getPaymentId(),
			cancelReason,
			response.getCode(),
			responseCode.getDescription()
		);
		markCancelFailed(attempt, toAttemptFailCode(responseCode), responseCode.getDescription());
	}

	private NaverPayResponse<NaverPayApproveBody> requestApprove(PaymentAttempt attempt) {
		try {
			log.info(
				"NaverPay approve request: merchantPayKey={}, paymentId={}, amount={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				attempt.getAmount()
			);
			return naverPayClient.approve(attempt.getPaymentId());
		} catch (NaverPayException ex) {
			log.warn(
				"NaverPay approve request failed: merchantPayKey={}, paymentId={}, amount={}, message={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				attempt.getAmount(),
				ex.getMessage()
			);
			failApprove(attempt, toAttemptFailCode(ex), ex.getMessage());
			throw new PaymentException(toPaymentErrorCode(ex));
		}
	}

	private NaverPayResponse<NaverPayHistoryBody> requestAllHistory(PaymentAttempt attempt) {
		try {
			log.info(
				"NaverPay approval history request: merchantPayKey={}, paymentId={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId()
			);
			return naverPayClient.getAllHistory(attempt.getPaymentId());
		} catch (NaverPayException ex) {
			log.warn(
				"NaverPay approval history request failed: merchantPayKey={}, paymentId={}, message={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				ex.getMessage()
			);
			failApprove(attempt, toAttemptFailCode(ex), ex.getMessage());
			throw new PaymentException(toPaymentErrorCode(ex));
		}
	}

	private NaverPayResponse<NaverPayCancelBody> requestCancel(
		PaymentAttempt attempt,
		int cancelAmount,
		String cancelReason
	) {
		try {
			log.info(
				"NaverPay payment cancel request: merchantPayKey={}, paymentId={}, attemptAmount={}, cancelAmount={}, cancelReason={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				attempt.getAmount(),
				cancelAmount,
				cancelReason
			);
			return naverPayClient.cancel(
				NaverPayCancelRequest.builder()
					.paymentId(attempt.getPaymentId())
					.cancelAmount(cancelAmount)
					.cancelReason(cancelReason)
					.cancelRequester(NaverPayCancelRequester.CANCEL_BY_ADMIN)
					.taxScopeAmount(cancelAmount)
					.taxExScopeAmount(0)
					.build()
			);
		} catch (NaverPayException ex) {
			log.warn(
				"NaverPay payment cancel request failed: merchantPayKey={}, paymentId={}, attemptAmount={}, cancelAmount={}, cancelReason={}, message={}",
				attempt.getMerchantPayKey(),
				attempt.getPaymentId(),
				attempt.getAmount(),
				cancelAmount,
				cancelReason,
				ex.getMessage()
			);
			markCancelFailed(attempt, toAttemptFailCode(ex), ex.getMessage());
			throw new PaymentException(toPaymentErrorCode(ex));
		}
	}

	private NaverPayApproveResult toResult(String paymentId, NaverPayApproveStatus status) {
		return NaverPayApproveResult.builder()
			.pgPaymentId(paymentId)
			.status(status)
			.build();
	}

	private NaverPayApproveResult toResult(Payment payment) {
		return NaverPayApproveResult.builder()
			.pgPaymentId(payment.getPgPaymentId())
			.status(toApproveResultStatus(payment.getStatus()))
			.build();
	}

	private NaverPayApproveStatus toApproveResultStatus(PaymentStatus status) {
		return switch (status) {
			case COMPLETED -> NaverPayApproveStatus.SUCCESS;
			case CANCELED -> throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		};
	}

	private PaymentAttemptFailCode toAttemptFailCode(NaverPayException ex) {
		return switch (ex.getErrorCode()) {
			case NETWORK -> PaymentAttemptFailCode.PG_NETWORK_ERROR;
			case SERVER_ERROR -> PaymentAttemptFailCode.PG_SERVER_ERROR;
			case INVALID_RESPONSE -> PaymentAttemptFailCode.PG_INVALID_RESPONSE;
			case CLIENT_ERROR, AUTHENTICATION -> PaymentAttemptFailCode.PG_REQUEST_REJECTED;
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayException ex) {
		return switch (ex.getErrorCode()) {
			case NETWORK -> PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR;
			case SERVER_ERROR -> PaymentErrorCode.PAYMENT_PG_SERVER_ERROR;
			case INVALID_RESPONSE -> PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE;
			case CLIENT_ERROR, AUTHENTICATION -> PaymentErrorCode.PAYMENT_PG_REQUEST_REJECTED;
		};
	}

	private PaymentAttemptFailCode toAttemptFailCode(NaverPayApproveCode approveCode) {
		return switch (approveCode) {
			case TIME_EXPIRED -> PaymentAttemptFailCode.TIME_EXPIRED;
			case INVALID_MERCHANT -> PaymentAttemptFailCode.INVALID_MERCHANT;
			case OWNER_AUTH_FAIL -> PaymentAttemptFailCode.OWNER_AUTH_FAILED;
			case NOT_ENOUGH_ACCOUNT_BALANCE -> PaymentAttemptFailCode.NOT_ENOUGH_ACCOUNT_BALANCE;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentAttemptFailCode.PG_MAINTENANCE;
			case FAIL -> PaymentAttemptFailCode.PG_REQUEST_REJECTED;
			default -> throw new IllegalArgumentException(
				"Non-failure approve code cannot be mapped to attempt fail code: " + approveCode);
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayApproveCode approveCode) {
		return switch (approveCode) {
			case TIME_EXPIRED -> PaymentErrorCode.PAYMENT_TIME_EXPIRED;
			case INVALID_MERCHANT -> PaymentErrorCode.PAYMENT_INVALID_MERCHANT;
			case OWNER_AUTH_FAIL -> PaymentErrorCode.PAYMENT_OWNER_AUTH_FAILED;
			case NOT_ENOUGH_ACCOUNT_BALANCE -> PaymentErrorCode.PAYMENT_NOT_ENOUGH_ACCOUNT_BALANCE;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentErrorCode.PAYMENT_PG_MAINTENANCE;
			default -> PaymentErrorCode.PAYMENT_APPROVE_FAILED;
		};
	}

	private PaymentAttemptFailCode toAttemptFailCode(NaverPayCancelCode cancelCode) {
		return switch (cancelCode) {
			case INVALID_MERCHANT -> PaymentAttemptFailCode.INVALID_MERCHANT;
			case INVALID_PAYMENT_ID -> PaymentAttemptFailCode.INVALID_PAYMENT_ID;
			case PRE_CANCEL_NOT_COMPLETE, CANCEL_NOT_COMPLETE -> PaymentAttemptFailCode.CANCEL_PROCESS_FAILED;
			case MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentAttemptFailCode.PG_MAINTENANCE;
			case OVER_REMAIN_AMOUNT, CANCEL_DEADLINE_EXPIRED, TAX_SCOPE_AMT_GREATER_THAN_REMAIN_ERROR,
				 TAX_SCOPE_AMOUNT_ERROR, REST_AMOUNT_DIFF, INVALID_DISCOUNT_CANCEL_CONDITION,
				 FAIL -> PaymentAttemptFailCode.PG_REQUEST_REJECTED;
			default -> throw new IllegalArgumentException(
				"Non-failure cancel code cannot be mapped to attempt fail code: " + cancelCode);
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayHistoryCode historyCode) {
		return switch (historyCode) {
			case INVALID_MERCHANT -> PaymentErrorCode.PAYMENT_INVALID_MERCHANT;
			case MAINTENANCE_ONGOING -> PaymentErrorCode.PAYMENT_PG_MAINTENANCE;
			case REQUIRE_CONDITION, FAIL -> PaymentErrorCode.PAYMENT_APPROVE_STATUS_CHECK_FAILED;
			default -> PaymentErrorCode.PAYMENT_APPROVE_STATUS_CHECK_FAILED;
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

	private void failApprove(
		PaymentAttempt attempt,
		PaymentAttemptFailCode failCode,
		String failDetail
	) {
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

	private void markCancelFailed(
		PaymentAttempt attempt,
		PaymentAttemptFailCode failCode,
		String failDetail
	) {
		paymentAttemptService.failCancelAttempt(
			attempt.getMerchantPayKey(),
			attempt.getProvider(),
			attempt.getPaymentId(),
			failCode,
			failDetail,
			LocalDateTime.now()
		);
	}

	private NaverPayApproveBody.Detail getDetail(NaverPayResponse<NaverPayApproveBody> response) {
		try {
			return response.getBody().getDetail();
		} catch (NullPointerException ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
		}
	}

	private NaverPayHistoryBody.History getLatestHistory(NaverPayResponse<NaverPayHistoryBody> response) {
		try {
			return response.getBody().getList().getLast();
		} catch (NullPointerException ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
		} catch (NoSuchElementException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
		}
	}

}
