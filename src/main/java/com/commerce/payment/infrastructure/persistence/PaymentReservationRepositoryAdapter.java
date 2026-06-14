package com.commerce.payment.infrastructure.persistence;

import java.util.Optional;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentReservationRepositoryAdapter implements PaymentReservationRepository {

	private final JpaPaymentReservationRepository jpaPaymentReservationRepository;

	@Override
	public PaymentReservation save(PaymentReservation reservation) {
		return jpaPaymentReservationRepository.saveAndFlush(reservation);
	}

	/**
	 * 예약 소비(use) 전용 저장 경로.
	 * saveAndFlush의 조기 flush가 @Version 낙관적 락 충돌을 이 메서드 호출 안에서 확정한다(load-bearing).
	 * 진 쪽의 ObjectOptimisticLockingFailureException을 차단용 PaymentException(PAYMENT_RESERVATION_ALREADY_USED)로 변환한다.
	 * catch 블록에서 추가 DB 쓰기를 하지 않는다 — 충돌 후 트랜잭션은 rollback-only 상태다.
	 */
	@Override
	public PaymentReservation saveUsed(PaymentReservation reservation) {
		try {
			return jpaPaymentReservationRepository.saveAndFlush(reservation);
		} catch (ObjectOptimisticLockingFailureException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESERVATION_ALREADY_USED);
		}
	}

	@Override
	public Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey) {
		return jpaPaymentReservationRepository.findByMerchantPayKey(merchantPayKey);
	}

	@Override
	public Optional<PaymentReservation> findByMemberIdAndMerchantPayKey(Long memberId, String merchantPayKey) {
		return jpaPaymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, merchantPayKey);
	}

	@Override
	public Optional<PaymentReservation> findReserved(Long orderId, PaymentProvider provider) {
		return jpaPaymentReservationRepository.findReserved(orderId, provider);
	}
}
