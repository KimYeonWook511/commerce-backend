package com.commerce.payment.naverpay.application;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.commerce.common.exception.CustomException;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentReservationStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
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
	private final PaymentReservationRepository paymentReservationRepository;
	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentApprovalAttemptService paymentApprovalAttemptService;
	private final PaymentApprovalCompensationService paymentApprovalCompensationService;

	public NaverPayApproveResponse approve(Long memberId, String merchantPayKey, String pgPaymentId) {
		PaymentReservation reservation = paymentReservationRepository.findByMerchantPayKey(merchantPayKey)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!reservation.getMemberId().equals(memberId)) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_MEMBER_MISMATCH);
		}

		// 주문 존재 여부 사전 검증
		Order order = orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// UNKNOWN 행이 있는 주문은 추가 결제 시도 차단 (ADR-6)
		if (paymentRepository.existsUnknownByOrderId(reservation.getOrderId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		}

		// USED Reservation: 같은 merchantPayKey로 redirect가 두 번째로 도착한 경우 → 멱등 응답 (ADR-5)
		if (reservation.getStatus() == PaymentReservationStatus.USED) {
			Payment existing = paymentRepository.findApproveSucceeded(merchantPayKey)
				.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
			return toResponse(existing);
		}

		Payment attempt = paymentApprovalAttemptService.create(reservation, pgPaymentId);
		return processApproveAttempt(attempt);
	}

	private NaverPayApproveResponse processApproveAttempt(Payment attempt) {
		return switch (attempt.getStatus()) {
			case REQUESTED -> processApproveRequest(attempt);
			case SUCCEEDED -> toResponse(attempt);
			case FAILED -> throw new PaymentException(toPaymentErrorCode(attempt.getFailCode()));
			case UNKNOWN -> throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		};
	}

	private NaverPayApproveResponse processApproveRequest(Payment attempt) {
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
			// PG 호출 timeout / 네트워크 단절: 결과 불명 흔적 보존 + 사용자 재시도 차단 (ADR-6)
			case UNKNOWN -> {
				paymentApprovalAttemptService.markUnknownIfRequested(
					attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
					result.getFailDetail(), LocalDateTime.now()
				);
				throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			}
		};
	}

	private NaverPayApproveResponse processAlreadyComplete(Payment attempt) {
		NaverPayHistoryResult result = naverPayGateway.getApprovalHistory(attempt.getPgPaymentId());

		if (result.getStatus() == NaverPayHistoryResult.Status.FAILED) {
			throw new PaymentException(result.getErrorCode());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.APPROVED) {
			if (!attempt.getMerchantPayKey().equals(result.getMerchantPayKey())) {
				paymentApprovalAttemptService.failIfRequested(
					attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
					PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치", LocalDateTime.now()
				);
				throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
			}
			return completeVerifiedApproval(attempt, result.getMerchantPayKey(), result.getTotalPayAmount());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.CANCELED) {
			paymentApprovalAttemptService.failIfRequested(
				attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId(),
				PaymentFailCode.ALREADY_CANCELED, "이미 취소된 결제", LocalDateTime.now()
			);
			throw new PaymentException(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
		}

		throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
	}

	private NaverPayApproveResponse completeVerifiedApproval(
		Payment attempt,
		String responseMerchantPayKey,
		int responseTotalAmount
	) {
		try {
			attempt.verifyApprovedResponse(responseMerchantPayKey, responseTotalAmount);

			Payment completed = paymentApprovalService.succeedApproval(attempt, LocalDateTime.now());
			return toResponse(completed);
		} catch (DataIntegrityViolationException ex) {
			// uk_payment_approved_order_key 위반: 같은 orderId에 이미 SUCCEEDED APPROVE 행 존재 → 이중 결제
			log.error("uk_payment_approved_order_key 위반 — 이중 결제: orderId={} merchantPayKey={}",
				attempt.getOrderId(), attempt.getMerchantPayKey(), ex);
			paymentApprovalCompensationService.compensateDuplicateApproval(attempt, this::pgCancel);
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
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
					paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
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
			paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
			throw ex;
		} catch (Exception ex) {
			log.error(
				"NaverPay approve complete failed by unexpected error: merchantPayKey={}, pgPaymentId={}",
				attempt.getMerchantPayKey(),
				attempt.getPgPaymentId(),
				ex
			);
			paymentApprovalCompensationService.compensateUnexpected(attempt, ex, PaymentFailCode.APPROVE_PROCESS_FAILED, this::pgCancel);
			throw ex;
		}
	}

	private CancelOutcome pgCancel(Payment cancelAttempt, String cancelReason) {
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

	private NaverPayApproveResponse toResponse(Payment attempt) {
		return NaverPayApproveResponse.builder()
			.pgPaymentId(attempt.getPgPaymentId())
			.status(NaverPayApproveStatus.SUCCESS)
			.build();
	}

	private PaymentErrorCode toPaymentErrorCode(PaymentFailCode failCode) {
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
