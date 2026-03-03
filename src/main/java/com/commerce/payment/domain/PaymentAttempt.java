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

@Entity
@Table(
	name = "tbl_payment_attempt",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_payment_attempt_merchant_pay_key_payment_id",
			columnNames = {"merchant_pay_key", "payment_id"}
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
	private PaymentAttemptStatus status;

	@Enumerated(EnumType.STRING)
	private PaymentAttemptReasonCode reasonCode;

	private String reasonDetail;

	private LocalDateTime respondedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private PaymentAttempt(
		String merchantPayKey,
		String paymentId,
		int amount,
		PaymentProvider provider,
		PaymentAttemptStatus status,
		PaymentAttemptReasonCode reasonCode,
		String reasonDetail,
		LocalDateTime respondedAt
	) {
		this.merchantPayKey = merchantPayKey;
		this.paymentId = paymentId;
		this.amount = amount;
		this.provider = provider;
		this.status = status;
		this.reasonCode = reasonCode;
		this.reasonDetail = reasonDetail;
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
			.status(PaymentAttemptStatus.APPROVE_REQUESTED)
			.build();
	}

	public void approveSucceed(LocalDateTime respondedAt) {
		this.status = PaymentAttemptStatus.APPROVE_SUCCEEDED;
		this.reasonCode = null;
		this.reasonDetail = null;
		this.respondedAt = respondedAt;
	}

	public void approveFail(PaymentAttemptReasonCode reasonCode, String reasonDetail, LocalDateTime respondedAt) {
		this.status = PaymentAttemptStatus.APPROVE_FAILED;
		this.reasonCode = reasonCode;
		this.reasonDetail = reasonDetail;
		this.respondedAt = respondedAt;
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
			.status(PaymentAttemptStatus.CANCEL_REQUESTED)
			.build();
	}

	public void cancelSucceed(LocalDateTime respondedAt) {
		this.status = PaymentAttemptStatus.CANCEL_SUCCEEDED;
		this.reasonCode = null;
		this.reasonDetail = null;
		this.respondedAt = respondedAt;
	}

	public void cancelFail(PaymentAttemptReasonCode reasonCode, String reasonDetail, LocalDateTime respondedAt) {
		this.status = PaymentAttemptStatus.CANCEL_FAILED;
		this.reasonCode = reasonCode;
		this.reasonDetail = reasonDetail;
		this.respondedAt = respondedAt;
	}
}
