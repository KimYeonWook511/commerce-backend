package com.commerce.payment.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentApprovalRecordService {

	private final PaymentRepository paymentRepository;
	private final PaymentReservationRepository paymentReservationRepository;

	/**
	 * reservation.use() + Payment 생성을 한 트랜잭션으로 묶어 원자성을 보장한다.
	 * 재시도 시 payment가 이미 존재하면 use를 건너뛰고 기존 payment를 반환한다.
	 */
	@Transactional
	public Payment create(PaymentReservation reservation, String pgPaymentId) {
		return paymentRepository.findApprovePayment(
				reservation.getMerchantPayKey(), reservation.getProvider(), pgPaymentId)
			.orElseGet(() -> {
				reservation.use();
				paymentReservationRepository.saveUsed(reservation);
				return paymentRepository.save(
					Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId)
				);
			});
	}

	/**
	 * APPROVE 결제 이력을 FAILED로 전이하는 transition (별도 빈의 public @Transactional).
	 * find → 도메인 전이(fail 가드) → saveChecked. 충돌도 가드 위반도 catch하지 않고 도메인 예외를 전파한다.
	 * 단조 종착(skip)이 필요한 호출처(보상/실시간)는 useCase의 private skip 래퍼에서 PAYMENT_CONCURRENTLY_MODIFIED·
	 * PAYMENT_STATUS_TRANSITION_NOT_ALLOWED·PAYMENT_RECORD_NOT_FOUND를 흡수한다(트랜잭션 경계 밖). 무조건 전이(대사)는 그대로 전파한다.
	 * 보상 대상 approve 는 실시간 경로에서는 REQUESTED, 대사 경로에서는 UNKNOWN 으로 진입하므로 둘 다 FAILED 로 확정한다.
	 */
	@Transactional
	public void fail(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.fail(failCode, failDetail, respondedAt);
		paymentRepository.saveChecked(payment);
	}

	/**
	 * APPROVE 결제 이력을 UNKNOWN으로 전이하는 transition (별도 빈의 public @Transactional).
	 * find → 도메인 전이(markUnknown 가드: REQUESTED 아니면 PAYMENT_STATUS_TRANSITION_NOT_ALLOWED) → saveChecked.
	 * PG 호출 timeout / 네트워크 단절 시 결과 불명 흔적을 보존한다.
	 * 충돌·가드 위반을 catch하지 않고 전파한다 — skip 판단은 useCase의 private 래퍼(트랜잭션 경계 밖)가 담당한다.
	 */
	@Transactional
	public void markUnknown(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.markUnknown(failDetail, respondedAt);
		paymentRepository.saveChecked(payment);
	}

	/**
	 * APPROVE 결제 이력을 escalation 표시하는 transition (별도 빈의 public @Transactional).
	 * find → 도메인 가드(escalate) → saveChecked. escalation 대상이 아니면(이미 escalation됐거나 종착) no-op으로 false를 반환한다.
	 * 가드 통과해 escalatedAt을 기록·저장하면 이 트랜잭션이 통지 주체이므로 true를 반환한다.
	 * 동시 시도 중 진 쪽은 saveChecked가 PAYMENT_CONCURRENTLY_MODIFIED를 던지고, skip(통지 안 함)은 useCase가 담당한다.
	 */
	@Transactional
	public boolean escalate(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime now
	) {
		Payment payment = paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		if (!payment.escalate(now)) {
			return false;
		}
		paymentRepository.saveChecked(payment);
		return true;
	}
}
