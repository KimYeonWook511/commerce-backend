package com.commerce.payment.domain.repository;

import java.util.Optional;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;

public interface PaymentReservationRepository {

	PaymentReservation save(PaymentReservation reservation);

	// 예약 소비(use) 전용 저장 경로. @Version 낙관적 락 충돌 시 PaymentException(PAYMENT_RESERVATION_ALREADY_USED)로 매핑.
	PaymentReservation saveUsed(PaymentReservation reservation);

	Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey);

	Optional<PaymentReservation> findByMemberIdAndMerchantPayKey(Long memberId, String merchantPayKey);

	// 같은 (orderId, provider)의 RESERVED 예약을 만료 여부와 무관하게 조회한다.
	// uk_payment_reservation_reserved_key 덕에 결과는 0 또는 1개. 재사용/만료 판정은 도메인(isReusableFor)이 담당.
	Optional<PaymentReservation> findReserved(Long orderId, PaymentProvider provider);
}
