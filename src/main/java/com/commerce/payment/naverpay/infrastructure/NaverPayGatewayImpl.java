package com.commerce.payment.naverpay.infrastructure;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;

import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.infrastructure.client.NaverPayClient;
import com.commerce.payment.naverpay.infrastructure.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.infrastructure.client.request.NaverPayCancelRequester;
import com.commerce.payment.naverpay.infrastructure.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.infrastructure.code.NaverPayApproveCode;
import com.commerce.payment.naverpay.infrastructure.code.NaverPayCancelCode;
import com.commerce.payment.naverpay.infrastructure.code.NaverPayHistoryCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverPayGatewayImpl implements NaverPayGateway {

	private final NaverPayClient naverPayClient;

	@Override
	public NaverPayApproveResult approve(String pgPaymentId) {
		NaverPayResponse<NaverPayApproveBody> response;
		try {
			log.info("네이버페이 승인 요청 pgPaymentId={}", pgPaymentId);
			response = naverPayClient.approve(pgPaymentId);
		} catch (NaverPayException ex) {
			log.warn("네이버페이 승인 호출 실패 pgPaymentId={} message={}", pgPaymentId, ex.getMessage());
			return NaverPayApproveResult.failed(toAttemptFailCode(ex), toPaymentErrorCode(ex), ex.getMessage());
		}

		NaverPayApproveCode code = NaverPayApproveCode.from(response.getCode());
		log.info("네이버페이 승인 응답 pgPaymentId={} code={}", pgPaymentId, response.getCode());
		if (code.isSuccess()) {
			try {
				NaverPayApproveBody.Detail detail = response.getBody().getDetail();
				return NaverPayApproveResult.success(detail.getMerchantPayKey(), detail.getTotalPayAmount());
			} catch (NullPointerException ex) {
				log.warn("네이버페이 승인 응답 파싱 실패 pgPaymentId={}", pgPaymentId);
				return NaverPayApproveResult.failed(
					PaymentAttemptFailCode.PG_INVALID_RESPONSE,
					PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE,
					"네이버페이 응답 처리에 실패했습니다"
				);
			}
		}
		if (code.isAlreadyOnGoing()) {
			return NaverPayApproveResult.processing();
		}
		if (code.isAlreadyComplete()) {
			return NaverPayApproveResult.alreadyComplete();
		}

		log.warn("네이버페이 승인 실패 pgPaymentId={} code={} message={}",
			pgPaymentId, response.getCode(), code.getDescription());
		return NaverPayApproveResult.failed(toAttemptFailCode(code), toPaymentErrorCode(code), code.getDescription());
	}

	@Override
	public NaverPayHistoryResult getApprovalHistory(String pgPaymentId) {
		NaverPayResponse<NaverPayHistoryBody> response;
		try {
			log.info("네이버페이 이력조회 요청 pgPaymentId={}", pgPaymentId);
			response = naverPayClient.getAllHistory(pgPaymentId);
		} catch (NaverPayException ex) {
			log.warn("네이버페이 이력조회 호출 실패 pgPaymentId={} message={}", pgPaymentId, ex.getMessage());
			return NaverPayHistoryResult.failed(toPaymentErrorCode(ex));
		}

		NaverPayHistoryCode code = NaverPayHistoryCode.from(response.getCode());
		if (!code.isSuccess()) {
			log.warn("네이버페이 이력조회 실패 pgPaymentId={} code={}", pgPaymentId, response.getCode());
			return NaverPayHistoryResult.failed(toPaymentErrorCode(code));
		}
		log.info("네이버페이 이력조회 응답 pgPaymentId={} code={}", pgPaymentId, response.getCode());

		NaverPayHistoryBody.History history;
		try {
			history = response.getBody().getList().getLast();
		} catch (NullPointerException ex) {
			return NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE);
		} catch (NoSuchElementException ex) {
			return NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND);
		}

		if (history.isCompletedApproval()) {
			return NaverPayHistoryResult.approved(history.getMerchantPayKey(), history.getTotalPayAmount());
		}
		if (history.isCanceledApproval()) {
			return NaverPayHistoryResult.canceled();
		}
		return NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND);
	}

	@Override
	public NaverPayCancelResult cancel(String pgPaymentId, int cancelAmount, String cancelReason) {
		NaverPayResponse<?> response;
		try {
			log.info("네이버페이 취소 요청 pgPaymentId={} cancelAmount={}", pgPaymentId, cancelAmount);
			response = naverPayClient.cancel(
				NaverPayCancelRequest.builder()
					.paymentId(pgPaymentId)
					.cancelAmount(cancelAmount)
					.cancelReason(cancelReason)
					.cancelRequester(NaverPayCancelRequester.CANCEL_BY_ADMIN)
					.taxScopeAmount(cancelAmount)
					.taxExScopeAmount(0)
					.build()
			);
		} catch (NaverPayException ex) {
			log.warn("네이버페이 취소 호출 실패 pgPaymentId={} message={}", pgPaymentId, ex.getMessage());
			return NaverPayCancelResult.failed(toAttemptFailCode(ex), ex.getMessage());
		}

		NaverPayCancelCode code = NaverPayCancelCode.from(response.getCode());
		log.info("네이버페이 취소 응답 pgPaymentId={} code={}", pgPaymentId, response.getCode());
		if (code.isAlreadyOnGoing()) {
			return NaverPayCancelResult.processing();
		}
		if (code.isSuccess()) {
			return NaverPayCancelResult.success();
		}
		if (code.isAlreadyCanceled()) {
			return NaverPayCancelResult.alreadyCanceled();
		}

		log.warn("네이버페이 취소 실패 pgPaymentId={} code={} message={}",
			pgPaymentId, response.getCode(), code.getDescription());
		return NaverPayCancelResult.failed(toAttemptFailCode(code), code.getDescription());
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

	private PaymentAttemptFailCode toAttemptFailCode(NaverPayApproveCode code) {
		return switch (code) {
			case TIME_EXPIRED -> PaymentAttemptFailCode.TIME_EXPIRED;
			case INVALID_MERCHANT -> PaymentAttemptFailCode.INVALID_MERCHANT;
			case OWNER_AUTH_FAIL -> PaymentAttemptFailCode.OWNER_AUTH_FAILED;
			case NOT_ENOUGH_ACCOUNT_BALANCE -> PaymentAttemptFailCode.NOT_ENOUGH_ACCOUNT_BALANCE;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentAttemptFailCode.PG_MAINTENANCE;
			case FAIL -> PaymentAttemptFailCode.PG_REQUEST_REJECTED;
			default -> throw new IllegalArgumentException(
				"Non-failure approve code cannot be mapped to attempt fail code: " + code);
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayApproveCode code) {
		return switch (code) {
			case TIME_EXPIRED -> PaymentErrorCode.PAYMENT_TIME_EXPIRED;
			case INVALID_MERCHANT -> PaymentErrorCode.PAYMENT_INVALID_MERCHANT;
			case OWNER_AUTH_FAIL -> PaymentErrorCode.PAYMENT_OWNER_AUTH_FAILED;
			case NOT_ENOUGH_ACCOUNT_BALANCE -> PaymentErrorCode.PAYMENT_NOT_ENOUGH_ACCOUNT_BALANCE;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentErrorCode.PAYMENT_PG_MAINTENANCE;
			default -> PaymentErrorCode.PAYMENT_APPROVE_FAILED;
		};
	}

	private PaymentAttemptFailCode toAttemptFailCode(NaverPayCancelCode code) {
		return switch (code) {
			case INVALID_MERCHANT -> PaymentAttemptFailCode.INVALID_MERCHANT;
			case INVALID_PG_PAYMENT_ID -> PaymentAttemptFailCode.INVALID_PG_PAYMENT_ID;
			case PRE_CANCEL_NOT_COMPLETE, CANCEL_NOT_COMPLETE -> PaymentAttemptFailCode.CANCEL_PROCESS_FAILED;
			case MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentAttemptFailCode.PG_MAINTENANCE;
			case OVER_REMAIN_AMOUNT, CANCEL_DEADLINE_EXPIRED, TAX_SCOPE_AMT_GREATER_THAN_REMAIN_ERROR,
				 TAX_SCOPE_AMOUNT_ERROR, REST_AMOUNT_DIFF, INVALID_DISCOUNT_CANCEL_CONDITION,
				 FAIL -> PaymentAttemptFailCode.PG_REQUEST_REJECTED;
			default -> throw new IllegalArgumentException(
				"Non-failure cancel code cannot be mapped to attempt fail code: " + code);
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayHistoryCode code) {
		return switch (code) {
			case INVALID_MERCHANT -> PaymentErrorCode.PAYMENT_INVALID_MERCHANT;
			case MAINTENANCE_ONGOING -> PaymentErrorCode.PAYMENT_PG_MAINTENANCE;
			case REQUIRE_CONDITION, FAIL -> PaymentErrorCode.PAYMENT_APPROVE_STATUS_CHECK_FAILED;
			default -> PaymentErrorCode.PAYMENT_APPROVE_STATUS_CHECK_FAILED;
		};
	}

	private PaymentErrorCode toPaymentErrorCode(NaverPayErrorCode errorCode) {
		return switch (errorCode) {
			case NETWORK -> PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR;
			case SERVER_ERROR -> PaymentErrorCode.PAYMENT_PG_SERVER_ERROR;
			case INVALID_RESPONSE -> PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE;
			case CLIENT_ERROR, AUTHENTICATION -> PaymentErrorCode.PAYMENT_PG_REQUEST_REJECTED;
		};
	}
}
