package com.commerce.payment.legacy.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "tbl_legacy_payment",
	uniqueConstraints = {
		// NULL trick: APPROVE+SUCCEEDED 일 때만 orderId 값이 채워져 unique 제약이 동작함
		@UniqueConstraint(name = "uk_payment_approved_order_key", columnNames = {"approved_order_key"}),
		// Flyway V6 기존 unique 미러링: H2 test 스키마에도 CANCEL 멱등 제약이 생기도록. prod는 validate라 무해.
		@UniqueConstraint(name = "uk_payment_merchant_pay_key_provider_pg_payment_id_type",
			columnNames = {"merchant_pay_key", "provider", "pg_payment_id", "type"})
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	private Long version;

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

	@Column(name = "escalated_at")
	private LocalDateTime escalatedAt;

	@Column(name = "next_reconcile_at")
	private LocalDateTime nextReconcileAt;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	// NULL trick: APPROVE+SUCCEEDED 일 때만 orderId 값이 채워짐
	@Column(name = "approved_order_key")
	private Long approvedOrderKey;

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
		return new Payment(
			reservation.getMerchantPayKey(),
			pgPaymentId,
			reservation.getAmount(),
			reservation.getProvider(),
			type,
			PaymentStatus.REQUESTED,
			null,
			null,
			null,
			reservation.getOrderId(),
			null
		);
	}

	public static Payment createCancelRequested(
		Long orderId,
		String merchantPayKey,
		String pgPaymentId,
		int amount,
		PaymentProvider provider
	) {
		return new Payment(
			merchantPayKey,
			pgPaymentId,
			amount,
			provider,
			PaymentType.CANCEL,
			PaymentStatus.REQUESTED,
			null,
			null,
			null,
			orderId,
			null
		);
	}

	public void succeed(LocalDateTime respondedAt) {
		if (this.status != PaymentStatus.REQUESTED && this.status != PaymentStatus.UNKNOWN) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentStatus.SUCCEEDED;
		// APPROVE 타입은 approvedOrderKey 에 orderId 를 채워 uk_payment_approved_order_key 제약을 활성화
		if (this.type == PaymentType.APPROVE) {
			this.approvedOrderKey = this.orderId;
		}
		this.respondedAt = respondedAt;
	}

	public void fail(PaymentFailCode failCode, String failDetail, LocalDateTime respondedAt) {
		if (this.status != PaymentStatus.REQUESTED && this.status != PaymentStatus.UNKNOWN) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentStatus.FAILED;
		this.failCode = failCode;
		this.failDetail = failDetail;
		this.respondedAt = respondedAt;
	}

	public void markUnknown(String failDetail, LocalDateTime respondedAt) {
		if (this.status != PaymentStatus.REQUESTED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		this.status = PaymentStatus.UNKNOWN;
		this.failDetail = failDetail;
		this.respondedAt = respondedAt;
	}

	/**
	 * escalation 가능 상태(UNKNOWN/REQUESTED)이고 아직 escalation 되지 않았으면 escalatedAt을 기록하고 true를 반환한다.
	 * 이미 escalation됐거나 종착 상태이면 false를 반환한다(멱등/skip).
	 * 통지 주체 여부는 save 성공(@Version)으로 최종 확정한다.
	 */
	public boolean escalate(LocalDateTime now) {
		if (this.status != PaymentStatus.UNKNOWN && this.status != PaymentStatus.REQUESTED) {
			return false;
		}
		if (this.escalatedAt != null) {
			return false;
		}
		this.escalatedAt = now;
		return true;
	}

	/**
	 * CANCEL 결제 전용 escalation. UNKNOWN/REQUESTED 외에 FAILED도 escalation 가능하다.
	 * CANCEL FAILED는 확정적 환불 실패이므로 자동 재시도 없이 사람에게 넘긴다.
	 * 이미 escalation됐으면 false를 반환한다(멱등/skip).
	 * 통지 주체 여부는 save 성공(@Version)으로 최종 확정한다.
	 */
	public boolean escalateCancelPayment(LocalDateTime now) {
		if (this.type != PaymentType.CANCEL) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		}
		if (this.status != PaymentStatus.UNKNOWN
			&& this.status != PaymentStatus.REQUESTED
			&& this.status != PaymentStatus.FAILED) {
			return false;
		}
		if (this.escalatedAt != null) {
			return false;
		}
		this.escalatedAt = now;
		return true;
	}

	/**
	 * 대사 재조회를 미룬다. next_reconcile_at = now + backoff 만 세팅하고 status·respondedAt 등 다른 필드는 바꾸지 않는다.
	 * wait 판정 후 "다음 재조회를 미룬다"는 의도만 표현한다.
	 */
	public void delayReconcile(LocalDateTime now, Duration backoff) {
		this.nextReconcileAt = now.plus(backoff);
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
