package com.commerce.payment.domain;

import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "tbl_payment_reservation",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_payment_reservation_merchant_pay_key", columnNames = {"merchant_pay_key"}),
		// NULL trick: RESERVED 상태일 때만 값이 있어 unique 제약이 동작하고, USED 이후에는 null로 설정
		@UniqueConstraint(name = "uk_payment_reservation_reserved_key", columnNames = {"reserved_key"})
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class PaymentReservation extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	private Long version;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 32)
	private PaymentProvider provider;

	@Column(nullable = false, length = 64)
	private String merchantPayKey;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 32)
	private PaymentReservationStatus status;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	// NULL trick: RESERVED 상태이면 "{orderId}:{provider.name()}" 값, USED 이후이면 null
	@Column(length = 96)
	private String reservedKey;

	@Builder(access = AccessLevel.PRIVATE)
	private PaymentReservation(
		Long orderId,
		Long memberId,
		int amount,
		PaymentProvider provider,
		String merchantPayKey,
		PaymentReservationStatus status,
		LocalDateTime expiresAt,
		String reservedKey
	) {
		this.orderId = orderId;
		this.memberId = memberId;
		this.amount = amount;
		this.provider = provider;
		this.merchantPayKey = merchantPayKey;
		this.status = status;
		this.expiresAt = expiresAt;
		this.reservedKey = reservedKey;
	}

	public static PaymentReservation createReserved(
		Long orderId,
		Long memberId,
		int amount,
		PaymentProvider provider,
		String merchantPayKey,
		LocalDateTime expiresAt
	) {
		return PaymentReservation.builder()
			.orderId(orderId)
			.memberId(memberId)
			.amount(amount)
			.provider(provider)
			.merchantPayKey(merchantPayKey)
			.status(PaymentReservationStatus.RESERVED)
			.expiresAt(expiresAt)
			.reservedKey(orderId + ":" + provider.name())
			.build();
	}

	public boolean isReusableFor(Long memberId, PaymentProvider provider, int amount, LocalDateTime now) {
		return this.status == PaymentReservationStatus.RESERVED
			&& this.expiresAt.isAfter(now)
			&& this.memberId.equals(memberId)
			&& this.provider == provider
			&& this.amount == amount;
	}

	public void use() {
		if (this.status != PaymentReservationStatus.RESERVED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentReservationStatus.USED;
		this.reservedKey = null;
	}

	// 만료/무효화된 예약을 회수한다. reservedKey를 비워 uk_payment_reservation_reserved_key 점유를 해제하므로
	// 같은 (orderId, provider)로 새 예약을 발급할 수 있다 (ADR-5 박제 자동 복구).
	public void expire() {
		if (this.status != PaymentReservationStatus.RESERVED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentReservationStatus.EXPIRED;
		this.reservedKey = null;
	}
}
