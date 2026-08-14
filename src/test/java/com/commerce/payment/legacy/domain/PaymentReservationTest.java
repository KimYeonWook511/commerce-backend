package com.commerce.payment.legacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentReservationTest {

	@DisplayName("createReserved 호출 시 상태가 RESERVED이고 reservedKey가 '{orderId}:{provider.name()}'이다")
	@Test
	void createReserved_whenCalled_setReservedStatusAndReservedKey() {
		// when
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));

		// then
		assertThat(reservation.getStatus()).isEqualTo(PaymentReservationStatus.RESERVED);
		assertThat(reservation.getReservedKey()).isEqualTo("1:NAVERPAY");
		assertThat(reservation.getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(reservation.getOrderId()).isEqualTo(1L);
		assertThat(reservation.getMemberId()).isEqualTo(1L);
		assertThat(reservation.getAmount()).isEqualTo(1000);
		assertThat(reservation.getProvider()).isEqualTo(PaymentProvider.NAVERPAY);
	}

	@DisplayName("use 호출 시 상태가 USED가 되고 reservedKey가 null이 된다")
	@Test
	void use_whenCalled_setUsedStatusAndNullReservedKey() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));

		// when
		reservation.use();

		// then
		assertThat(reservation.getStatus()).isEqualTo(PaymentReservationStatus.USED);
		assertThat(reservation.getReservedKey()).isNull();
	}

	@DisplayName("expire 호출 시 상태가 EXPIRED가 되고 reservedKey가 null이 된다")
	@Test
	void expire_whenCalled_setExpiredStatusAndNullReservedKey() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));

		// when
		reservation.expire();

		// then: reservedKey를 비워 uk_payment_reservation_reserved_key 점유를 해제한다
		assertThat(reservation.getStatus()).isEqualTo(PaymentReservationStatus.EXPIRED);
		assertThat(reservation.getReservedKey()).isNull();
	}

	@DisplayName("RESERVED 외 상태에서 expire 호출 시 예외가 발생한다")
	@Test
	void expire_whenNotReserved_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		reservation.use();

		// when & then
		org.assertj.core.api.Assertions.assertThatThrownBy(reservation::expire)
			.isInstanceOf(com.commerce.payment.legacy.domain.exception.PaymentException.class);
	}

	@DisplayName("RESERVED이고 만료되지 않았으며 memberId/provider/amount가 일치하면 isReusableFor이 true를 반환한다")
	@Test
	void isReusableFor_whenReservedAndNotExpiredAndMatchingConditions_returnTrue() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));

		// when & then
		assertThat(reservation.isReusableFor(1L, PaymentProvider.NAVERPAY, 1000, now)).isTrue();
	}

	@DisplayName("RESERVED 외 상태에서 use 호출 시 예외가 발생한다")
	@Test
	void use_whenNotReserved_throwException() {
		// given
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", LocalDateTime.now().plusMinutes(15));
		reservation.use();

		// when & then
		org.assertj.core.api.Assertions.assertThatThrownBy(reservation::use)
			.isInstanceOf(com.commerce.payment.legacy.domain.exception.PaymentException.class);
	}

	@DisplayName("USED 상태이면 isReusableFor이 false를 반환한다")
	@Test
	void isReusableFor_whenUsed_returnFalse() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));
		reservation.use();

		// when & then
		assertThat(reservation.isReusableFor(1L, PaymentProvider.NAVERPAY, 1000, now)).isFalse();
	}

	@DisplayName("만료된 예약이면 isReusableFor이 false를 반환한다")
	@Test
	void isReusableFor_whenExpired_returnFalse() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.minusMinutes(1));

		// when & then
		assertThat(reservation.isReusableFor(1L, PaymentProvider.NAVERPAY, 1000, now)).isFalse();
	}

	@DisplayName("금액이 다르면 isReusableFor이 false를 반환한다")
	@Test
	void isReusableFor_whenDifferentAmount_returnFalse() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1", now.plusMinutes(15));

		// when & then
		assertThat(reservation.isReusableFor(1L, PaymentProvider.NAVERPAY, 2000, now)).isFalse();
	}
}
