package com.commerce.payment.naverpay.application.usecase;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.commerce.common.exception.CustomException;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentReservationStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.application.service.PaymentApprovalRecordService;
import com.commerce.payment.application.usecase.PaymentApprovalCompensationUseCase;
import com.commerce.payment.application.service.PaymentApprovalService;
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
@Component
@RequiredArgsConstructor
public class NaverPayApprovalUseCase {

	// transition(별도 빈)이 던지는 도메인 예외 중 best-effort 기록에서 skip할 코드 집합 (ADR-L2).
	// 충돌(다른 주체가 먼저 종착) / 가드 위반(이미 종착) / 이력 없음은 단조 종착이므로 흡수한다. 흡수는 트랜잭션 경계 밖(useCase)에서만 한다.
	private static final Set<PaymentErrorCode> SKIPPABLE = EnumSet.of(
		PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED,
		PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED,
		PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND);

	private final NaverPayGateway naverPayGateway;
	private final PaymentReservationRepository paymentReservationRepository;
	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentApprovalRecordService paymentApprovalRecordService;
	private final PaymentApprovalCompensationUseCase paymentApprovalCompensationService;

	public NaverPayApproveResponse approve(Long memberId, String merchantPayKey, String pgPaymentId) {
		PaymentReservation reservation = paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, merchantPayKey)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RESERVATION_NOT_FOUND));

		// 주문 존재 여부 사전 검증
		Order order = orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// UNKNOWN 행이 있는 주문은 추가 결제 시도 차단 (ADR-6)
		if (paymentRepository.existsUnknownByOrderId(reservation.getOrderId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		}

		// USED Reservation: 같은 merchantPayKey로 redirect가 두 번째로 도착한 경우 (ADR-5)
		// 기존 APPROVE payment를 상태별로 재처리한다. SUCCEEDED면 멱등 응답, REQUESTED면 PG 재확인
		// (PROCESSING/중단으로 미완료된 시도가 영구 차단되지 않도록), FAILED/UNKNOWN이면 해당 사유로 응답.
		// 요청 pgPaymentId의 payment가 없으면 이미 소비된 예약을 다른 pgPaymentId로 재사용하려는 중복 요청이므로
		// PAYMENT_RESERVATION_ALREADY_USED로 차단한다. 이로써 동시 이중 use의 진 쪽이 concurrent(saveUsed 낙관적 락)·
		// sequential(이 분기) 경로에서 같은 에러코드로 일관되게 차단된다.
		if (reservation.getStatus() == PaymentReservationStatus.USED) {
			Payment payment = paymentRepository.findApprovePayment(merchantPayKey, reservation.getProvider(), pgPaymentId)
				.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RESERVATION_ALREADY_USED));
			return processApprovePayment(payment);
		}

		// 이미 APPROVE·SUCCEEDED 결제가 있는 주문은 새 승인 진입 차단 (ADR-L2)
		if (paymentRepository.existsApprovedByOrderId(reservation.getOrderId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
		}

		Payment payment = paymentApprovalRecordService.create(reservation, pgPaymentId);
		return processApprovePayment(payment);
	}

	private NaverPayApproveResponse processApprovePayment(Payment payment) {
		return switch (payment.getStatus()) {
			case REQUESTED -> processApproveRequest(payment);
			case SUCCEEDED -> toResponse(payment);
			case FAILED -> throw new PaymentException(toPaymentErrorCode(payment.getFailCode()));
			case UNKNOWN -> throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		};
	}

	private NaverPayApproveResponse processApproveRequest(Payment payment) {
		NaverPayApproveResult result = naverPayGateway.approve(payment.getPgPaymentId());

		return switch (result.getStatus()) {
			case PROCESSING -> toResponse(payment.getPgPaymentId(), NaverPayApproveStatus.PROCESSING);
			case ALREADY_COMPLETE -> processAlreadyComplete(payment);
			case FAILED -> {
				failSkippable(
					payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
					result.getFailCode(), result.getFailDetail(), LocalDateTime.now()
				);
				throw new PaymentException(result.getErrorCode());
			}
			case SUCCESS ->
				completeVerifiedApproval(payment, result.getMerchantPayKey(), result.getTotalPayAmount());
			// PG 호출 timeout / 네트워크 단절: 결과 불명 흔적 보존 + 사용자 재시도 차단 (ADR-6)
			case UNKNOWN -> {
				markUnknownSkippable(
					payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
					result.getFailDetail(), LocalDateTime.now()
				);
				throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			}
		};
	}

	private NaverPayApproveResponse processAlreadyComplete(Payment payment) {
		NaverPayHistoryResult result = naverPayGateway.getApprovalHistory(payment.getPgPaymentId());

		// AlreadyComplete 는 PG 가 이미 처리한 상태이므로 이력조회가 결과 불명이면 UNKNOWN 보존이 가장 강하게 적용된다.
		// approve 직접 경로의 case UNKNOWN 과 동일하게 흔적을 남기고 재시도를 차단한다 (ADR-027, #219).
		if (result.getStatus() == NaverPayHistoryResult.Status.UNKNOWN) {
			markUnknownSkippable(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
				result.getFailDetail(), LocalDateTime.now()
			);
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.FAILED) {
			throw new PaymentException(result.getErrorCode());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.APPROVED) {
			if (!payment.getMerchantPayKey().equals(result.getMerchantPayKey())) {
				failSkippable(
					payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
					PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH, "가맹점 결제 키 불일치", LocalDateTime.now()
				);
				throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
			}
			return completeVerifiedApproval(payment, result.getMerchantPayKey(), result.getTotalPayAmount());
		}

		if (result.getStatus() == NaverPayHistoryResult.Status.CANCELED) {
			failSkippable(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
				PaymentFailCode.ALREADY_CANCELED, "이미 취소된 결제", LocalDateTime.now()
			);
			throw new PaymentException(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
		}

		throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
	}

	private NaverPayApproveResponse completeVerifiedApproval(
		Payment payment,
		String responseMerchantPayKey,
		int responseTotalAmount
	) {
		try {
			payment.verifyApprovedResponse(responseMerchantPayKey, responseTotalAmount);

			Payment completed = paymentApprovalService.succeedApproval(payment, LocalDateTime.now());
			return toResponse(completed);
		} catch (PaymentException ex) {
			log.error(
				"NaverPay approve complete failed by payment error: merchantPayKey={}, pgPaymentId={}, responseMerchantPayKey={}, responseTotalAmount={}, errorCode={}",
				payment.getMerchantPayKey(),
				payment.getPgPaymentId(),
				responseMerchantPayKey,
				responseTotalAmount,
				ex.getErrorCode(),
				ex
			);
			switch ((PaymentErrorCode)ex.getErrorCode()) {
				case PAYMENT_MERCHANT_KEY_MISMATCH ->
					paymentApprovalCompensationService.compensateMerchantKeyMismatch(payment);
				case PAYMENT_AMOUNT_MISMATCH ->
					paymentApprovalCompensationService.compensateAmountMismatch(payment, responseTotalAmount, this::pgCancel);
				case PAYMENT_DUPLICATE ->
					paymentApprovalCompensationService.compensateDuplicatePayment(payment, ex, this::pgCancel);
				// 정상 승인 후 기록 실패는 보상 없이 전파. approve REQUESTED 유지 → reconcile self-heal (ADR-L1)
				default -> {}
			}
			throw ex;
		} catch (CustomException ex) {
			log.error(
				"NaverPay approve complete failed by custom error: merchantPayKey={}, pgPaymentId={}, errorCode={}",
				payment.getMerchantPayKey(),
				payment.getPgPaymentId(),
				ex.getErrorCode(),
				ex
			);
			throw ex;
		} catch (Exception ex) {
			log.error(
				"NaverPay approve complete failed by unexpected error: merchantPayKey={}, pgPaymentId={}",
				payment.getMerchantPayKey(),
				payment.getPgPaymentId(),
				ex
			);
			throw ex;
		}
	}

	/**
	 * fail transition을 호출하되 best-effort 흡수 대상(ADR-L2 SKIPPABLE) 도메인 예외는 skip한다.
	 * transition은 별도 빈(public @Transactional)이라 충돌 시 그 트랜잭션만 깨끗이 rollback되고, 흡수 catch는 트랜잭션 경계 밖에서 일어난다.
	 */
	private void failSkippable(
		String merchantPayKey, PaymentProvider provider, String pgPaymentId,
		PaymentFailCode failCode, String failDetail, LocalDateTime respondedAt
	) {
		try {
			paymentApprovalRecordService.fail(merchantPayKey, provider, pgPaymentId, failCode, failDetail, respondedAt);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("결제 실패 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), merchantPayKey, pgPaymentId);
				return;
			}
			throw ex;
		}
	}

	/**
	 * markUnknown transition을 호출하되 best-effort 흡수 대상(ADR-L2 SKIPPABLE) 도메인 예외는 skip한다. (failSkippable과 동일 구조)
	 */
	private void markUnknownSkippable(
		String merchantPayKey, PaymentProvider provider, String pgPaymentId,
		String failDetail, LocalDateTime respondedAt
	) {
		try {
			paymentApprovalRecordService.markUnknown(merchantPayKey, provider, pgPaymentId, failDetail, respondedAt);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("UNKNOWN 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), merchantPayKey, pgPaymentId);
				return;
			}
			throw ex;
		}
	}

	private CancelOutcome pgCancel(Payment cancelPayment, String cancelReason) {
		NaverPayCancelResult result = naverPayGateway.cancel(
			cancelPayment.getPgPaymentId(), cancelPayment.getAmount(), cancelReason
		);
		return switch (result.getStatus()) {
			case SUCCESS, ALREADY_CANCELED -> CancelOutcome.success();
			case PROCESSING -> CancelOutcome.processing();
			case FAILED -> CancelOutcome.failed(result.getFailCode(), result.getFailDetail());
			case UNKNOWN -> CancelOutcome.unknown(result.getFailDetail());
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
			.status(NaverPayApproveStatus.SUCCESS)
			.build();
	}

	private PaymentErrorCode toPaymentErrorCode(PaymentFailCode failCode) {
		return switch (failCode) {
			case TIME_EXPIRED -> PaymentErrorCode.PAYMENT_TIME_EXPIRED;
			case ORDER_CANCELED -> PaymentErrorCode.PAYMENT_ALREADY_CANCELED;
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
