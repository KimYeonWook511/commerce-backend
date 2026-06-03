package com.commerce.payment.domain;

import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tbl_payment", uniqueConstraints = {
	@UniqueConstraint(name = "uk_payment_order_id", columnNames = {"order_id"}),
	@UniqueConstraint(name = "uk_payment_merchant_pay_key", columnNames = {"merchant_pay_key"}),
	@UniqueConstraint(name = "uk_payment_pg_payment_id", columnNames = {"pg_payment_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private PaymentStatus status;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private PaymentProvider provider;

	@Column(length = 64)
	private String merchantPayKey;

	@Column(length = 64)
	private String pgPaymentId;

	private LocalDateTime approvedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Payment(
		Long orderId,
		int amount,
		PaymentStatus status,
		PaymentProvider provider,
		String merchantPayKey,
		String pgPaymentId,
		LocalDateTime approvedAt
	) {
		this.orderId = orderId;
		this.amount = amount;
		this.status = status;
		this.provider = provider;
		this.merchantPayKey = merchantPayKey;
		this.pgPaymentId = pgPaymentId;
		this.approvedAt = approvedAt;
	}

	public static Payment createCompleted(
		Long orderId,
		int amount,
		PaymentProvider provider,
		String merchantPayKey,
		String pgPaymentId,
		LocalDateTime approvedAt
	) {
		return Payment.builder()
			.orderId(orderId)
			.amount(amount)
			.status(PaymentStatus.COMPLETED)
			.provider(provider)
			.merchantPayKey(merchantPayKey)
			.pgPaymentId(pgPaymentId)
			.approvedAt(approvedAt)
			.build();
	}
}
