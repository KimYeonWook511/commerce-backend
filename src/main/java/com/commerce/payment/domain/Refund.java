package com.commerce.payment.domain;

import java.time.LocalDateTime;
import java.util.Arrays;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * "얼마를 왜 돌려준다"는 의도 하나. 판정과 한도 계산이 기대는 금액의 정본이다.
 *
 * <p>결제와 각자 aggregate 루트라 결제를 식별자로 참조한다. 경계가 실제로 일하는 것은 환불을 만들 때
 * 뿐이고, 환불 하나의 상태를 바꾸는 일은 한도를 바꾸지 않아 결제가 알 필요가 없다. 그래서 자기 낙관
 * 락을 갖고 자기 전이 메서드로 바뀐다 — 결제 버전을 빌려 쓰면 대사가 도는 동안 회원의 환불 요청이
 * 낙관 락 충돌로 밀린다.
 */
@Entity
@Table(
	name = "tbl_refund",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_refund_refund_key", columnNames = {"refund_key"}),
		@UniqueConstraint(name = "uk_refund_payment_idempotency",
			columnNames = {"payment_id", "requester", "idempotency_key"}),
		@UniqueConstraint(name = "uk_refund_pg_idempotency_key", columnNames = {"pg_idempotency_key"})
	},
	indexes = {
		@Index(name = "idx_refund_payment", columnList = "payment_id"),
		@Index(name = "idx_refund_status_reconcile",
			columnList = "status, reconcile_count, last_reconcile_at")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Refund extends BaseTimeEntity {

	/**
	 * 환불 시도 키와 결제사 호출 멱등키를 사건 키에서 파생할 때 쓰는 구분자.
	 * 만들기와 맞추기가 한 규칙에 묶여야 이력에서 우리 시도를 집어낼 수 있으므로 이 자리 하나에 둔다.
	 */
	private static final String ATTEMPT_SEPARATOR = "-";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	@Column(nullable = false)
	private Long version;

	/** 어느 결제의 환불인가. 독립 aggregate라 식별자 참조이며, 소유 확인이 이 값을 타고 들어온다 */
	@Column(name = "payment_id", nullable = false)
	private Long paymentId;

	/** 환불 사건 키. 시도 번호를 붙인 환불 시도 키로 결제사에 보내고, 이력에서 이 값을 접두어로 찾는다 */
	@Column(name = "refund_key", nullable = false, length = 40)
	private String refundKey;

	/**
	 * 환불 요청 멱등키. 회원 환불은 밖에서 받은 값을, 시스템이 만드는 환불은 사유 값을 담는다.
	 * 비워 두면 유일 검사에서 빠져 DB가 중복을 못 막고, 같은 자리가 두 번 돌면 환불이 둘 생긴다.
	 */
	@Column(name = "idempotency_key", nullable = false, length = 64)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private RefundRequester requester;

	/** 이번 환불 금액. 만들어진 뒤 바뀌지 않는다 — 결제 행의 누적 환불액이 그 전제 위에 서 있다 */
	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private RefundReason reason;

	/**
	 * 결제사 호출 멱등키. 사건 키에 시도 번호를 붙여 파생하므로 시도 번호가 오르면 함께 새것이 된다.
	 * 같은 시도를 다시 보낼 때는 번호가 그대로라 같은 값이 나가고, 되돌아오는 이전 응답이 두 번째
	 * 회수 수단이 된다.
	 */
	@Column(name = "pg_idempotency_key", nullable = false, length = 64)
	private String pgIdempotencyKey;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private RefundStatus status;

	/** 결제사가 발급한 거래 번호. 취소라는 거래 하나를 가리키며 판정에는 쓰지 않는다 */
	@Column(name = "pg_transaction_id", length = 64)
	private String pgTransactionId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "review_code")
	private RefundReviewCode reviewCode;

	@Column(name = "review_detail")
	private String reviewDetail;

	/** 마지막으로 결제사를 부른 시각. 재전송마다 갱신된다 — 그러지 않으면 다른 대사가 같은 환불을 또 보낸다 */
	@Column(name = "last_requested_at")
	private LocalDateTime lastRequestedAt;

	/** 마지막으로 대사가 이 건을 집은 시각. 뜻과 채우는 자리가 결제와 같다 */
	@Column(name = "last_reconcile_at")
	private LocalDateTime lastReconcileAt;

	/** 마지막으로 운영자에게 알린 시각. 통지를 보낸 뒤에 찍는다 */
	@Column(name = "last_notify_at")
	private LocalDateTime lastNotifyAt;

	/**
	 * 시도 번호. 환불 시도 키와 결제사 호출 멱등키에 붙일 번호를 만들고, 0이면 한 번도 부르지 않은
	 * 건임을 알려준다. 결과를 모를 때 다시 보내는 것은 같은 키로 부르므로 오르지 않는다.
	 */
	@Column(name = "attempt_seq", nullable = false)
	private int attemptSeq;

	/** 대사가 이 건을 몇 번 집었나. 결제사에 시킨 횟수와 세는 대상이 다르다 */
	@Column(name = "reconcile_count", nullable = false)
	private int reconcileCount;

	private Refund(
		Long paymentId,
		String refundKey,
		RefundRequester requester,
		String idempotencyKey,
		int amount,
		RefundReason reason
	) {
		this.paymentId = paymentId;
		this.refundKey = refundKey;
		this.requester = requester;
		this.idempotencyKey = idempotencyKey;
		this.amount = amount;
		this.reason = reason;
		this.status = RefundStatus.REQUESTED;
		this.attemptSeq = 0;
		this.reconcileCount = 0;
		this.pgIdempotencyKey = attemptKey(refundKey, 0);
	}

	/**
	 * 환불 사건을 연다. 생성 불변식이 사는 유일한 관문이다 — 금액이 0보다 큰지, 요청자를 가리지 않고
	 * 요청 키가 있는지를 여기서 지킨다. 사건 키는 결제사를 부르기 전에 정해져야 하므로 여기서 받는다.
	 */
	public static Refund open(
		Long paymentId,
		String refundKey,
		RefundRequester requester,
		String idempotencyKey,
		int amount,
		RefundReason reason
	) {
		if (amount <= 0) {
			throw new PaymentException(PaymentErrorCode.REFUND_AMOUNT_INVALID);
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new PaymentException(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REQUIRED);
		}
		return new Refund(paymentId, refundKey, requester, idempotencyKey, amount, reason);
	}

	/** 결제사 호출 직전. 첫 발송이라 시도 번호가 오르고 그 번호에서 호출 멱등키가 새로 파생된다 */
	public void markInProgress(LocalDateTime requestedAt) {
		requireStatusIn(RefundStatus.REQUESTED);
		openNextAttempt();
		this.lastRequestedAt = requestedAt;
		this.status = RefundStatus.IN_PROGRESS;
	}

	/** 응답을 못 받아 결과를 모른다 */
	public void markUnknown() {
		requireStatusIn(RefundStatus.IN_PROGRESS);
		this.status = RefundStatus.UNKNOWN;
	}

	/** 환불이 완료됐다. 자동으로 갈 수 있는 유일한 종착이다 */
	public void complete(String pgTransactionId) {
		requireStatusIn(RefundStatus.IN_PROGRESS, RefundStatus.UNKNOWN);
		this.pgTransactionId = pgTransactionId;
		this.status = RefundStatus.SUCCEEDED;
	}

	/** 자동으로는 더 진행할 수 없다. 검토 코드가 채워졌다는 것이 곧 이 상태라 함께 채운다 */
	public void flagForReview(RefundReviewCode reviewCode, String reviewDetail) {
		requireStatusIn(RefundStatus.IN_PROGRESS, RefundStatus.UNKNOWN);
		this.reviewCode = reviewCode;
		this.reviewDetail = reviewDetail;
		this.status = RefundStatus.MANUAL_REVIEW;
	}

	/**
	 * 다시 시도할 수 있는 실패를 받았다. 상태는 그대로 두고 새 시도를 열어 다음 호출이 새 키로 나가게
	 * 한다. 상태를 되돌리면 확인이 낡고 대사의 다시 집는 간격도 잃는다.
	 */
	public void recordRetryableFailure() {
		requireStatusIn(RefundStatus.IN_PROGRESS, RefundStatus.UNKNOWN);
		openNextAttempt();
	}

	/** 대사가 이 건을 집었다. 확정했든 못 했든 집을 때마다 회차가 오른다 */
	public void recordReconciled(LocalDateTime pickedAt) {
		this.lastReconcileAt = pickedAt;
		this.reconcileCount++;
	}

	/** 통지를 보낸 뒤에 남긴다 */
	public void recordNotified(LocalDateTime notifiedAt) {
		this.lastNotifyAt = notifiedAt;
	}

	private void openNextAttempt() {
		this.attemptSeq++;
		this.pgIdempotencyKey = attemptKey(this.refundKey, this.attemptSeq);
	}

	private static String attemptKey(String refundKey, int attemptSeq) {
		return refundKey + ATTEMPT_SEPARATOR + attemptSeq;
	}

	private void requireStatusIn(RefundStatus... allowed) {
		if (Arrays.stream(allowed).noneMatch(candidate -> candidate == this.status)) {
			throw new PaymentException(PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED);
		}
	}
}
