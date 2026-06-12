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

import com.commerce.common.jpa.JpaConfig;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentReservationStatus;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;

import com.commerce.payment.infrastructure.persistence.PaymentReservationRepositoryAdapter;
import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@Import({
	JpaConfig.class,
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

	// ─── findReserved ────────────────────────────────────────────────────────────

	@DisplayName("RESERVED 상태의 예약을 (orderId, provider)로 조회하면 반환한다")
	@Test
	void findReserved_whenReservedExists_returnsReservation() {
		// given
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1",
				LocalDateTime.now().plusMinutes(15)));
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result = reservationRepository.findReserved(1L, PaymentProvider.NAVERPAY);

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-1");
	}

	@DisplayName("만료 시각이 지났어도 status가 RESERVED면 조회된다 (reservedKey 회수 대상)")
	@Test
	void findReserved_whenExpiredButStillReserved_returnsReservation() {
		// given: expiresAt은 과거지만 아직 expire 전이라 status=RESERVED → reservedKey 점유 중
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-2",
				LocalDateTime.now().minusMinutes(1)));
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result = reservationRepository.findReserved(1L, PaymentProvider.NAVERPAY);

		// then: 만료됐어도 RESERVED 상태라 회수 대상으로 조회되어야 함
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-2");
	}

	@DisplayName("provider가 다르면 빈 결과를 반환한다")
	@Test
	void findReserved_whenDifferentProvider_returnsEmpty() {
		// given
		reservationRepository.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-3",
				LocalDateTime.now().plusMinutes(15)));
		em.flush();
		em.clear();

		// when: KAKAOPAY로 조회
		Optional<PaymentReservation> result = reservationRepository.findReserved(1L, PaymentProvider.KAKAOPAY);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("USED 상태의 예약은 findReserved 결과에서 제외된다")
	@Test
	void findReserved_whenUsed_returnsEmpty() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-4", LocalDateTime.now().plusMinutes(15));
		reservation.use();
		reservationRepository.save(reservation);
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result = reservationRepository.findReserved(1L, PaymentProvider.NAVERPAY);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("EXPIRED 상태의 예약은 findReserved 결과에서 제외된다")
	@Test
	void findReserved_whenExpiredStatus_returnsEmpty() {
		// given: expire로 회수된 행
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-5", LocalDateTime.now().plusMinutes(15));
		reservation.expire();
		reservationRepository.save(reservation);
		em.flush();
		em.clear();

		// when
		Optional<PaymentReservation> result = reservationRepository.findReserved(1L, PaymentProvider.NAVERPAY);

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("expire로 reservedKey를 회수하면 같은 (orderId, provider)로 새 예약을 저장할 수 있다")
	@Test
	void save_whenExpiredReleasedReservedKey_allowsNewReservationWithSameKey() {
		// given: RESERVED 저장 (reservedKey="1:NAVERPAY" 점유)
		PaymentReservation first = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-FIRST", LocalDateTime.now().plusMinutes(15));
		reservationRepository.save(first);
		em.flush();

		// when: 만료 회수(reservedKey=null) 후 같은 (orderId, provider)로 새 예약 저장
		first.expire();
		reservationRepository.save(first);
		em.flush();
		PaymentReservation second = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-SECOND", LocalDateTime.now().plusMinutes(15));
		reservationRepository.save(second);
		em.flush();
		em.clear();

		// then: uk_payment_reservation_reserved_key 위반 없이 저장 성공 (C2 회귀 방어)
		Optional<PaymentReservation> reserved = reservationRepository.findReserved(1L, PaymentProvider.NAVERPAY);
		assertThat(reserved).isPresent();
		assertThat(reserved.get().getMerchantPayKey()).isEqualTo("PAY-SECOND");
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
		reservation.use();
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
