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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "tbl_payment",
	uniqueConstraints = {
		// NULL trick: APPROVE+SUCCEEDED 일 때만 orderId 값이 채워져 unique 제약이 동작함
		@UniqueConstraint(name = "uk_payment_approved_order_key", columnNames = {"approved_order_key"})
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64)
	private String merchantPayKey;

	@Column(nullable = false, length = 64)
	private String pgPaymentId;

	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 32)
	private PaymentProvider provider;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 32)
	private PaymentType type;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 32)
	private PaymentStatus status;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private PaymentFailCode failCode;

	private String failDetail;

	private LocalDateTime respondedAt;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	// NULL trick: APPROVE+SUCCEEDED 일 때만 orderId 값이 채워짐
	@Column(name = "approved_order_key")
	private Long approvedOrderKey;

	@Builder(access = AccessLevel.PRIVATE)
	private Payment(
		String merchantPayKey,
		String pgPaymentId,
		int amount,
		PaymentProvider provider,
		PaymentType type,
		PaymentStatus status,
		PaymentFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt,
		Long orderId,
		Long approvedOrderKey
	) {
		this.merchantPayKey = merchantPayKey;
		this.pgPaymentId = pgPaymentId;
		this.amount = amount;
		this.provider = provider;
		this.type = type;
		this.status = status;
		this.failCode = failCode;
		this.failDetail = failDetail;
		this.respondedAt = respondedAt;
		this.orderId = orderId;
		this.approvedOrderKey = approvedOrderKey;
	}

	public static Payment createRequested(PaymentReservation reservation, PaymentType type, String pgPaymentId) {
		return Payment.builder()
			.merchantPayKey(reservation.getMerchantPayKey())
			.pgPaymentId(pgPaymentId)
			.amount(reservation.getAmount())
			.provider(reservation.getProvider())
			.type(type)
			.status(PaymentStatus.REQUESTED)
			.orderId(reservation.getOrderId())
			.build();
	}

	public static Payment createCancelRequested(
		Long orderId,
		String merchantPayKey,
		String pgPaymentId,
		int amount,
		PaymentProvider provider
	) {
		return Payment.builder()
			.orderId(orderId)
			.merchantPayKey(merchantPayKey)
			.pgPaymentId(pgPaymentId)
			.amount(amount)
			.provider(provider)
			.type(PaymentType.CANCEL)
			.status(PaymentStatus.REQUESTED)
			.build();
	}

	public void succeed(LocalDateTime respondedAt) {
		if (this.status != PaymentStatus.REQUESTED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentStatus.SUCCEEDED;
		// APPROVE 타입은 approvedOrderKey 에 orderId 를 채워 uk_payment_approved_order_key 제약을 활성화
		if (this.type == PaymentType.APPROVE) {
			this.approvedOrderKey = this.orderId;
		}
		this.respondedAt = respondedAt;
	}

	public void fail(PaymentFailCode failCode, String failDetail, LocalDateTime respondedAt) {
		if (this.status != PaymentStatus.REQUESTED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentStatus.FAILED;
		this.failCode = failCode;
		this.failDetail = failDetail;
		this.respondedAt = respondedAt;
	}

	public void markUnknown(String failDetail, LocalDateTime respondedAt) {
		if (this.status != PaymentStatus.REQUESTED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentStatus.UNKNOWN;
		this.failDetail = failDetail;
		this.respondedAt = respondedAt;
	}

	public void verifyApprovedResponse(String responseMerchantPayKey, int responseTotalAmount) {
		if (!this.merchantPayKey.equals(responseMerchantPayKey)) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
		}
		if (this.amount != responseTotalAmount) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}
	}
}
