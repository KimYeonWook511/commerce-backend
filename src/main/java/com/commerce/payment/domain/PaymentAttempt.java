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

@Entity
@Table(
	name = "tbl_payment_attempt",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_payment_attempt_merchant_pay_key_provider_payment_id_type",
			columnNames = {"merchant_pay_key", "provider", "payment_id", "type"}
		)
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class PaymentAttempt extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String merchantPayKey;

	@Column(nullable = false)
	private String paymentId;

	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentProvider provider;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentAttemptType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentAttemptStatus status;

	@Enumerated(EnumType.STRING)
	private PaymentAttemptFailCode failCode;

	private String failDetail;

	private LocalDateTime respondedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private PaymentAttempt(
		String merchantPayKey,
		String paymentId,
		int amount,
		PaymentProvider provider,
		PaymentAttemptType type,
		PaymentAttemptStatus status,
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		this.merchantPayKey = merchantPayKey;
		this.paymentId = paymentId;
		this.amount = amount;
		this.provider = provider;
		this.type = type;
		this.status = status;
		this.failCode = failCode;
		this.failDetail = failDetail;
		this.respondedAt = respondedAt;
	}

	public static PaymentAttempt createApproveRequested(
		String merchantPayKey,
		String paymentId,
		int amount,
		PaymentProvider provider
	) {
		return PaymentAttempt.builder()
			.merchantPayKey(merchantPayKey)
			.paymentId(paymentId)
			.amount(amount)
			.provider(provider)
			.type(PaymentAttemptType.APPROVE)
			.status(PaymentAttemptStatus.REQUESTED)
			.build();
	}

	public void succeed(LocalDateTime respondedAt) {
		if (this.status != PaymentAttemptStatus.REQUESTED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentAttemptStatus.SUCCEEDED;
		this.failCode = null;
		this.failDetail = null;
		this.respondedAt = respondedAt;
	}

	public void fail(PaymentAttemptFailCode failCode, String failDetail, LocalDateTime respondedAt) {
		if (this.status != PaymentAttemptStatus.REQUESTED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentAttemptStatus.FAILED;
		this.failCode = failCode;
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

	public static PaymentAttempt createCancelRequested(
		String merchantPayKey,
		String paymentId,
		int amount,
		PaymentProvider provider
	) {
		return PaymentAttempt.builder()
			.merchantPayKey(merchantPayKey)
			.paymentId(paymentId)
			.amount(amount)
			.provider(provider)
			.type(PaymentAttemptType.CANCEL)
			.status(PaymentAttemptStatus.REQUESTED)
			.build();
	}
}
