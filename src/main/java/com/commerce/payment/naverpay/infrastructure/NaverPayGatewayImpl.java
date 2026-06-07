package com.commerce.payment.naverpay.infrastructure;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;

import com.commerce.payment.domain.PaymentFailCode;
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
			if (isResultUnknown(ex.getErrorCode())) {
				// 네트워크/서버 오류/응답 해석 불가: PG 가 승인을 처리했는지 불명 → UNKNOWN.
				// FAILED 로 두면 재결제가 허용되어 이중결제가 발생할 수 있다.
				log.warn("네이버페이 승인 결과 불명 pgPaymentId={} errorCode={} message={}",
					pgPaymentId, ex.getErrorCode(), ex.getMessage());
				return NaverPayApproveResult.unknown("승인 결과 불명: " + ex.getMessage());
			}
			// 인증 실패 / 잘못된 요청: PG 가 처리하지 않았음이 확실 → FAILED
			log.warn("네이버페이 승인 호출 실패 pgPaymentId={} errorCode={} message={}",
				pgPaymentId, ex.getErrorCode(), ex.getMessage());
			return NaverPayApproveResult.failed(toFailCode(ex), toPaymentErrorCode(ex), ex.getMessage());
		}
		// NaverPayException 이 아닌 예외(우리 코드의 NPE 등 프로그래밍 버그)는 UNKNOWN 으로 흡수하지 않고
		// 그대로 전파해 안전망(500)에 위임한다. timeout/네트워크 단절은 NaverPayClient 가 이미
		// NaverPayException(NETWORK 등)으로 변환하므로 위 catch 에서 UNKNOWN 으로 분류된다.

		NaverPayApproveCode code = NaverPayApproveCode.from(response.getCode());
		log.info("네이버페이 승인 응답 pgPaymentId={} code={}", pgPaymentId, response.getCode());
		if (code.isSuccess()) {
			NaverPayApproveBody body = response.getBody();
			NaverPayApproveBody.Detail detail = (body == null) ? null : body.getDetail();
			if (detail == null || detail.getMerchantPayKey() == null) {
				// PG 가 Success 로 응답해 승인이 처리된 것이 확실한데 응답 본문(detail / merchantPayKey)이 비어 있는 경우다.
				// 외부 응답 이상(우리 코드 버그가 아님)이므로 결과 불명(UNKNOWN)으로 보존해 재시도를 차단한다.
				// FAILED 로 두면 재결제가 허용되어 이중결제가 발생한다.
				log.warn("네이버페이 승인 응답 본문 누락 pgPaymentId={}", pgPaymentId);
				return NaverPayApproveResult.unknown("승인 응답 처리 실패: 결과 확인 필요");
			}
			return NaverPayApproveResult.success(detail.getMerchantPayKey(), detail.getTotalPayAmount());
		}
		if (code.isAlreadyOnGoing()) {
			return NaverPayApproveResult.processing();
		}
		if (code.isAlreadyComplete()) {
			return NaverPayApproveResult.alreadyComplete();
		}

		log.warn("네이버페이 승인 실패 pgPaymentId={} code={} message={}",
			pgPaymentId, response.getCode(), code.getDescription());
		return NaverPayApproveResult.failed(toFailCode(code), toPaymentErrorCode(code), code.getDescription());
	}

	@Override
	public NaverPayHistoryResult getApprovalHistory(String pgPaymentId) {
		NaverPayResponse<NaverPayHistoryBody> response;
		try {
			log.info("네이버페이 이력조회 요청 pgPaymentId={}", pgPaymentId);
			response = naverPayClient.getAllHistory(pgPaymentId);
		} catch (NaverPayException ex) {
			if (isResultUnknown(ex.getErrorCode())) {
				// 네트워크/서버 오류/응답 해석 불가: 이력조회로 승인 결과를 확정하지 못함 → UNKNOWN.
				// FAILED 로 두면 UNKNOWN 흔적이 안 남아 "결제됐는데 미결제 박제" 가 된다 (ADR-027, #219).
				log.warn("네이버페이 이력조회 결과 불명 pgPaymentId={} errorCode={} message={}",
					pgPaymentId, ex.getErrorCode(), ex.getMessage());
				return NaverPayHistoryResult.unknown("이력조회 결과 불명: " + ex.getMessage());
			}
			// 인증 실패 / 잘못된 요청: PG 가 이력조회 요청을 거절했음이 확실 → FAILED
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
			// 응답은 왔으나 본문 구조가 비정상(해석 불가) → 결과 불명. 재시도 차단을 위해 UNKNOWN 보존.
			return NaverPayHistoryResult.unknown("이력조회 응답 해석 불가");
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
			if (isResultUnknown(ex.getErrorCode())) {
				// 네트워크/서버 오류/응답 해석 불가: PG 가 취소를 처리했는지 불명 → UNKNOWN.
				// FAILED 로 두면 PG 가 실제로 취소했어도 cancel 기록이 FAILED 로 박제돼 대사에서 누락된다 (#219).
				log.warn("네이버페이 취소 결과 불명 pgPaymentId={} errorCode={} message={}",
					pgPaymentId, ex.getErrorCode(), ex.getMessage());
				return NaverPayCancelResult.unknown("취소 결과 불명: " + ex.getMessage());
			}
			log.warn("네이버페이 취소 호출 실패 pgPaymentId={} message={}", pgPaymentId, ex.getMessage());
			return NaverPayCancelResult.failed(toFailCode(ex), ex.getMessage());
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
		return NaverPayCancelResult.failed(toFailCode(code), code.getDescription());
	}

	// PG 가 승인을 처리했을 가능성이 남는 오류는 결과 불명(UNKNOWN)으로 분류해 재결제(이중결제)를 막는다.
	// NETWORK(요청/응답 유실), SERVER_ERROR(PG 5xx 내부 처리 중 실패 가능), INVALID_RESPONSE(응답은 왔으나 해석 불가).
	// CLIENT_ERROR/AUTHENTICATION 은 요청이 거절돼 처리되지 않았음이 확실하므로 FAILED 로 둔다.
	private boolean isResultUnknown(NaverPayErrorCode errorCode) {
		return errorCode == NaverPayErrorCode.NETWORK
			|| errorCode == NaverPayErrorCode.SERVER_ERROR
			|| errorCode == NaverPayErrorCode.INVALID_RESPONSE;
	}

	private PaymentFailCode toFailCode(NaverPayException ex) {
		return switch (ex.getErrorCode()) {
			case NETWORK -> PaymentFailCode.PG_NETWORK_ERROR;
			case SERVER_ERROR -> PaymentFailCode.PG_SERVER_ERROR;
			case INVALID_RESPONSE -> PaymentFailCode.PG_INVALID_RESPONSE;
			case CLIENT_ERROR, AUTHENTICATION -> PaymentFailCode.PG_REQUEST_REJECTED;
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

	private PaymentFailCode toFailCode(NaverPayApproveCode code) {
		return switch (code) {
			case TIME_EXPIRED -> PaymentFailCode.TIME_EXPIRED;
			case INVALID_MERCHANT -> PaymentFailCode.INVALID_MERCHANT;
			case OWNER_AUTH_FAIL -> PaymentFailCode.OWNER_AUTH_FAILED;
			case NOT_ENOUGH_ACCOUNT_BALANCE -> PaymentFailCode.NOT_ENOUGH_ACCOUNT_BALANCE;
			case BANK_MAINTENANCE, MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentFailCode.PG_MAINTENANCE;
			case FAIL -> PaymentFailCode.PG_REQUEST_REJECTED;
			default -> throw new IllegalArgumentException(
				"Non-failure approve code cannot be mapped to fail code: " + code);
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

	private PaymentFailCode toFailCode(NaverPayCancelCode code) {
		return switch (code) {
			case INVALID_MERCHANT -> PaymentFailCode.INVALID_MERCHANT;
			case INVALID_PG_PAYMENT_ID -> PaymentFailCode.INVALID_PG_PAYMENT_ID;
			case PRE_CANCEL_NOT_COMPLETE, CANCEL_NOT_COMPLETE -> PaymentFailCode.CANCEL_PROCESS_FAILED;
			case MAINTENANCE_ONGOING, FAULT_CHECK_ONGOING -> PaymentFailCode.PG_MAINTENANCE;
			case OVER_REMAIN_AMOUNT, CANCEL_DEADLINE_EXPIRED, TAX_SCOPE_AMT_GREATER_THAN_REMAIN_ERROR,
				 TAX_SCOPE_AMOUNT_ERROR, REST_AMOUNT_DIFF, INVALID_DISCOUNT_CANCEL_CONDITION,
				 FAIL -> PaymentFailCode.PG_REQUEST_REJECTED;
			default -> throw new IllegalArgumentException(
				"Non-failure cancel code cannot be mapped to fail code: " + code);
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
