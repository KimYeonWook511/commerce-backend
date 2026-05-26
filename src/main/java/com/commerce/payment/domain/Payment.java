package com.commerce.payment.domain;

import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.order.domain.Order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tbl_payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false, unique = true)
	private Order order;

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

	@Column(unique = true)
	private String merchantPayKey;

	@Column(unique = true)
	private String pgPaymentId;

	private LocalDateTime approvedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Payment(
		Order order,
		int amount,
		PaymentStatus status,
		PaymentProvider provider,
		String merchantPayKey,
		String pgPaymentId,
		LocalDateTime approvedAt
	) {
		this.order = order;
		this.amount = amount;
		this.status = status;
		this.provider = provider;
		this.merchantPayKey = merchantPayKey;
		this.pgPaymentId = pgPaymentId;
		this.approvedAt = approvedAt;
	}

	public static Payment createCompleted(
		Order order,
		PaymentProvider provider,
		String merchantPayKey,
		String pgPaymentId,
		LocalDateTime approvedAt
	) {
		return Payment.builder()
			.order(order)
			.amount(order.getTotalPrice())
			.status(PaymentStatus.COMPLETED)
			.provider(provider)
			.merchantPayKey(merchantPayKey)
			.pgPaymentId(pgPaymentId)
			.approvedAt(approvedAt)
			.build();
	}
}
