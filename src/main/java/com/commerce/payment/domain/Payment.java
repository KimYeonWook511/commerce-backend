package com.commerce.payment.domain;

import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.common.util.UlidGenerator;
import com.commerce.order.domain.Order;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

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

@Entity
@Table(name = "tbl_payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY) // 실패한 이력을 관리할 거면 ManyToOne도 고려해야 할듯
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentProvider provider;

	@Column(unique = true)
	private String merchantPayKey;

	@Column(unique = true)
	private String pgPaymentId;

	private String failureReason;

	private LocalDateTime approvedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Payment(Order order, int amount, PaymentStatus status, PaymentProvider provider, String merchantPayKey) {
		this.order = order;
		this.amount = amount;
		this.status = status;
		this.provider = provider;
		this.merchantPayKey = merchantPayKey;
	}

	public static Payment create(Order order, int amount, PaymentProvider provider) {
		return Payment.builder()
			.order(order)
			.amount(amount)
			.status(PaymentStatus.PENDING)
			.provider(provider)
			.merchantPayKey("PAY-" + UlidGenerator.generate())
			.build();
	}

	public void complete(LocalDateTime approvedAt) {
		validatePending();
		this.status = PaymentStatus.COMPLETED;
		this.approvedAt = approvedAt;
		this.failureReason = null;
	}

	public void fail(String reason) {
		validatePending();
		this.status = PaymentStatus.FAILED;
		this.failureReason = reason;
		this.approvedAt = null;
	}

	public void cancel(String reason) {
		validatePending();
		this.status = PaymentStatus.CANCELED;
		this.failureReason = reason;
		this.approvedAt = null;
	}

	public void assignPgPaymentId(String pgPaymentId) {
		if (this.pgPaymentId != null) {
			return;
		}
		this.pgPaymentId = pgPaymentId;
	}

	private void validatePending() {
		if (this.status != PaymentStatus.PENDING) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		}
	}
}
