package com.commerce.payment.legacy.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.domain.repository.PaymentReservationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CreateApprovePaymentService {

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
}
