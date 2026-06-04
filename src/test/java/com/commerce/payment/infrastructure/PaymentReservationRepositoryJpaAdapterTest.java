package com.commerce.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentReservationStatus;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@Import({
	PaymentReservationRepositoryAdapter.class,
	PaymentReservationPersistenceTestSupport.class
})
class PaymentReservationRepositoryJpaAdapterTest {

	@Autowired
	private PaymentReservationRepository reservationRepository;

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private EntityManager em;

	// ─── findReusable ────────────────────────────────────────────────────────────

	@DisplayName("모든 조건이 일치하면 재사용 가능한 예약을 반환한다")
	@Test
	void findReusable_whenAllConditionsMatch_returnsReservation() {
		// given
		LocalDateTime now = LocalDateTime.now();
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1",
				now.plusMinutes(15)));
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result =
			reservationRepository.findReusable(1L, 1L, PaymentProvider.NAVERPAY, 1000, now);

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-1");
	}

	@DisplayName("provider가 다르면 재사용 가능한 예약이 없다")
	@Test
	void findReusable_whenProviderDiffers_returnsEmpty() {
		// given
		LocalDateTime now = LocalDateTime.now();
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-2",
				now.plusMinutes(15)));
		em.flush();
		em.clear();

		// when: KAKAOPAY로 조회
		Optional<PaymentReservation> result =
			reservationRepository.findReusable(1L, 1L, PaymentProvider.KAKAOPAY, 1000, now);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("memberId가 다르면 재사용 가능한 예약이 없다")
	@Test
	void findReusable_whenMemberDiffers_returnsEmpty() {
		// given
		LocalDateTime now = LocalDateTime.now();
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-3",
				now.plusMinutes(15)));
		em.flush();
		em.clear();

		// when: memberId=2로 조회
		Optional<PaymentReservation> result =
			reservationRepository.findReusable(1L, 2L, PaymentProvider.NAVERPAY, 1000, now);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("amount가 다르면 재사용 가능한 예약이 없다")
	@Test
	void findReusable_whenAmountDiffers_returnsEmpty() {
		// given
		LocalDateTime now = LocalDateTime.now();
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-4",
				now.plusMinutes(15)));
		em.flush();
		em.clear();

		// when: amount=2000으로 조회
		Optional<PaymentReservation> result =
			reservationRepository.findReusable(1L, 1L, PaymentProvider.NAVERPAY, 2000, now);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("만료된 예약은 재사용 가능한 예약 결과에서 제외된다")
	@Test
	void findReusable_whenExpired_returnsEmpty() {
		// given
		LocalDateTime now = LocalDateTime.now();
		// expiresAt이 now보다 이전 (만료됨)
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-5",
				now.minusMinutes(1)));
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result =
			reservationRepository.findReusable(1L, 1L, PaymentProvider.NAVERPAY, 1000, now);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("USED 상태의 예약은 재사용 가능한 예약 결과에서 제외된다")
	@Test
	void findReusable_whenUsed_returnsEmpty() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-6", now.plusMinutes(15));
		reservation.markUsed();
		reservationRepository.save(reservation);
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result =
			reservationRepository.findReusable(1L, 1L, PaymentProvider.NAVERPAY, 1000, now);

		// then
		assertThat(result).isEmpty();
	}

	// ─── findByMerchantPayKey ────────────────────────────────────────────────────

	@DisplayName("RESERVED 상태의 예약을 merchantPayKey로 조회하면 반환한다")
	@Test
	void findByMerchantPayKey_whenReservedExists_returnsReservation() {
		// given
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-R1",
				LocalDateTime.now().plusMinutes(15)));
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result = reservationRepository.findByMerchantPayKey("PAY-R1");

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getStatus()).isEqualTo(PaymentReservationStatus.RESERVED);
	}

	@DisplayName("USED 상태의 예약도 merchantPayKey로 조회하면 반환한다")
	@Test
	void findByMerchantPayKey_whenUsedExists_returnsReservation() {
		// given: RESERVED → USED 전이 후 저장
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-U1", LocalDateTime.now().plusMinutes(15));
		reservation.markUsed();
		reservationRepository.save(reservation);
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result = reservationRepository.findByMerchantPayKey("PAY-U1");

		// then: USED 상태도 조회됨 (step 3 멱등 응답 흡수 시 필요)
		assertThat(result).isPresent();
		assertThat(result.get().getStatus()).isEqualTo(PaymentReservationStatus.USED);
		assertThat(result.get().getReservedKey()).isNull();
	}

	@DisplayName("merchantPayKey에 해당하는 예약이 없으면 빈 결과를 반환한다")
	@Test
	void findByMerchantPayKey_whenNoReservation_returnsEmpty() {
		// when
		Optional<PaymentReservation> result = reservationRepository.findByMerchantPayKey("PAY-NONE");

		// then
		assertThat(result).isEmpty();
	}
}
