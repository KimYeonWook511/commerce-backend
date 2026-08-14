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
 * 한 번의 결제 시도. 결제창을 띄우기 전에 만들어지고 승인 결과로 종결되며, 실패하거나 중단된 시도도
 * 행으로 남는다. 주문·회원·환불은 식별자로만 참조한다.
 *
 * <p>활성 슬롯({@code activeOrderKey})이 "한 주문에 살아 있는 결제는 최대 하나"를 유일 제약으로
 * 표현한다. 살아 있는 동안 주문 식별자를 담고 종결되면 비운다 — MySQL이 NULL 중복을 허용하므로
 * 종결된 행은 얼마든지 쌓이고 값이 든 행은 언제나 최대 하나다.
 */
@Entity
@Table(
	name = "tbl_payment",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_payment_payment_key", columnNames = {"payment_key"}),
		@UniqueConstraint(name = "uk_payment_member_idempotency",
			columnNames = {"member_id", "idempotency_key"}),
		@UniqueConstraint(name = "uk_payment_active_order_key", columnNames = {"active_order_key"})
	},
	indexes = {
		@Index(name = "idx_payment_order", columnList = "order_id"),
		@Index(name = "idx_payment_status_reconcile",
			columnList = "status, reconcile_count, last_reconcile_at")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	/** 우리가 발급해 결제사에 보내는 결제 키. 시도마다 새로 발급한다 */
	@Column(name = "payment_key", nullable = false, length = 64)
	private String paymentKey;

	/** 밖에서 받은 결제 시작 요청 멱등키. 유일 범위가 회원이라 같은 주문을 다시 결제하는 재시도는 막지 않는다 */
	@Column(name = "idempotency_key", nullable = false, length = 64)
	private String idempotencyKey;

	/** 결제사가 발급한 결제 번호. 승인 인증 뒤에 채워지므로 그 전에는 없다 */
	@Column(name = "pg_payment_id", length = 64)
	private String pgPaymentId;

	/** 결제사가 발급한 거래 번호. 승인이라는 거래 하나를 가리키며 판정에는 쓰지 않는다 */
	@Column(name = "pg_transaction_id", length = 64)
	private String pgTransactionId;

	/** 결제 시작 시점 주문 금액의 사본 */
	@Column(nullable = false)
	private int amount;

	/** 결제사가 실제로 승인한 금액. 한도 계산의 기준이며 승인 결과를 확정하기 전에는 없다 */
	@Column(name = "approved_amount")
	private Integer approvedAmount;

	/**
	 * 이 결제에 딸린 환불 금액의 합. 한도 판정이 읽는 유일한 값이다.
	 * 이름에 합이라는 뜻을 넣은 것은 주문 취소 응답이 같은 자리에서 이번 건 하나의 금액을 가리키기
	 * 때문이다 — 이름이 같으면 그 값을 그대로 응답에 옮기는 실수가 난다.
	 */
	@Column(name = "total_refunded_amount", nullable = false)
	private int totalRefundedAmount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private PaymentPg pg;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private PaymentStatus status;

	/** 활성 슬롯. 살아 있으면 주문 식별자, 종결되면 NULL */
	@Column(name = "active_order_key")
	private Long activeOrderKey;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "close_code")
	private PaymentCloseCode closeCode;

	/** 결제사가 준 문구. 장애 조사에서 읽는 값이고 분기에 쓰지 않는다 */
	@Column(name = "close_detail")
	private String closeDetail;

	/** 마지막으로 결제사를 부른 시각. 부를 때마다 갱신한다 */
	@Column(name = "last_requested_at")
	private LocalDateTime lastRequestedAt;

	/** 마지막으로 대사가 이 건을 집은 시각. 확정했든 못 했든 남긴다 */
	@Column(name = "last_reconcile_at")
	private LocalDateTime lastReconcileAt;

	/** 마지막으로 운영자에게 알린 시각. 통지를 보낸 뒤에 찍는다 */
	@Column(name = "last_notify_at")
	private LocalDateTime lastNotifyAt;

	/**
	 * 시도 번호. 승인 호출 멱등키에 붙일 번호를 만든다.
	 * 부른 횟수가 아니다 — 결과를 모를 때는 같은 키로 다시 부르므로 그때는 오르지 않고, 다시 시도할 수
	 * 있는 실패를 받아 새 키를 만들 때만 오른다.
	 */
	@Column(name = "attempt_seq", nullable = false)
	private int attemptSeq;

	/**
	 * 대사가 이 건을 몇 번 집었나. 다시 집는 간격이 회차마다 벌어지므로 그 간격을 고르는 데 쓰며,
	 * 회수 횟수의 상한이 아니다.
	 */
	@Column(name = "reconcile_count", nullable = false)
	private int reconcileCount;

	private Payment(
		Long orderId,
		Long memberId,
		PaymentPg pg,
		String paymentKey,
		String idempotencyKey,
		int amount
	) {
		this.orderId = orderId;
		this.memberId = memberId;
		this.pg = pg;
		this.paymentKey = paymentKey;
		this.idempotencyKey = idempotencyKey;
		this.amount = amount;
		this.totalRefundedAmount = 0;
		this.attemptSeq = 0;
		this.reconcileCount = 0;
		changeStatus(PaymentStatus.READY);
	}

	/**
	 * 결제를 시작한다. 결제창을 띄우기 전에 행이 먼저 생기고 그 자리에서 활성 슬롯을 잡는다 —
	 * 승인 성공 시점에 잡으면 회원이 결제창에서 인증하는 사이 같은 주문에 다른 결제가 설 수 있다.
	 */
	public static Payment start(
		Long orderId,
		Long memberId,
		PaymentPg pg,
		String paymentKey,
		String idempotencyKey,
		int amount
	) {
		return new Payment(orderId, memberId, pg, paymentKey, idempotencyKey, amount);
	}

	/** 승인 호출 직전. 결제사 번호를 심고 첫 호출의 시도 번호를 올린다. 부르기 전에 따로 커밋한다 */
	public void markInProgress(String pgPaymentId, LocalDateTime requestedAt) {
		requireStatusIn(PaymentStatus.READY);
		this.pgPaymentId = pgPaymentId;
		this.attemptSeq++;
		this.lastRequestedAt = requestedAt;
		changeStatus(PaymentStatus.IN_PROGRESS);
	}

	/** 응답을 못 받아 승인 결과를 모른다 */
	public void markUnknown() {
		requireStatusIn(PaymentStatus.IN_PROGRESS);
		changeStatus(PaymentStatus.UNKNOWN);
	}

	/**
	 * 다른 결제의 승인이 이 결제의 승인이었음이 드러났다. 번호를 심고 결과 불명으로 두어 대사에 넘긴다.
	 * 확정하지 않는 것은 확정이냐 환불이냐가 주문 상태에 따라 갈리고 그 판정이 승인 확정 흐름에 이미
	 * 있기 때문이다. 승인을 아직 부르지 않은 행만 회수한다 — 그 밖의 상태는 각자 자기 경로로 풀린다.
	 */
	public void reclaim(String pgPaymentId) {
		requireStatusIn(PaymentStatus.READY);
		this.pgPaymentId = pgPaymentId;
		changeStatus(PaymentStatus.UNKNOWN);
	}

	/** 승인이 성립했고 우리도 받아들인다. 성공한 결제는 활성 슬롯을 계속 쥔다 */
	public void succeed(int approvedAmount, String pgTransactionId) {
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		recordApproval(approvedAmount, pgTransactionId);
		changeStatus(PaymentStatus.SUCCEEDED);
	}

	/** 승인을 불렀는데 성립하지 않았다 */
	public void fail(PaymentCloseCode closeCode, String closeDetail) {
		requireCloseCodeFor(closeCode, PaymentStatus.FAILED);
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		close(closeCode, closeDetail);
	}

	/**
	 * 승인은 났으나 우리가 모르는 경로로 이미 취소됐다. 돈이 이미 돌아갔으므로 환불을 만들지 않지만,
	 * 승인이 났었다는 사실이 그 행만 보고도 읽히도록 승인 금액을 함께 남긴다.
	 */
	public void failExternallyCanceled(String closeDetail, int approvedAmount, String pgTransactionId) {
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		recordApproval(approvedAmount, pgTransactionId);
		close(PaymentCloseCode.EXTERNALLY_CANCELED, closeDetail);
	}

	/**
	 * 승인은 났는데 우리가 그 결제를 받아들일 수 없다. 되돌릴 금액을 알아야 하므로 승인 금액을 함께
	 * 남긴다. 환불 생성은 이 메서드가 하지 않는다 — 결제를 종결하는 것과 별개의 일이다.
	 */
	public void reject(PaymentCloseCode closeCode, String closeDetail, int approvedAmount, String pgTransactionId) {
		requireCloseCodeFor(closeCode, PaymentStatus.REJECTED);
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		recordApproval(approvedAmount, pgTransactionId);
		close(closeCode, closeDetail);
	}

	/**
	 * 승인을 한 번도 부르지 않은 채 끝난다. 자리를 내준 것도 세션이 지난 것도 여기로 오며, 어느 쪽인지는
	 * 종결 코드가 말한다.
	 */
	public void expire(PaymentCloseCode closeCode) {
		requireCloseCodeFor(closeCode, PaymentStatus.EXPIRED);
		requireStatusIn(PaymentStatus.READY);
		close(closeCode, null);
	}

	/**
	 * 다시 시도할 수 있는 실패를 받았다. 상태는 그대로 두고 시도 번호만 올려 다음 호출이 새 키로
	 * 나가게 한다. 그 사실이 상태에 남지 않으므로 사유를 알게 된 이 자리에서 올린다.
	 */
	public void recordRetryableFailure() {
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		this.attemptSeq++;
	}

	/**
	 * 대사가 이 건을 집었다. 조회로 끝난 주기도 그 자리에서 다시 부른 주기도 하나로 세며, 결제사를
	 * 부르기 전에 따로 커밋한다 — 결과 반영과 묶으면 호출이 깨졌을 때 회차가 함께 롤백되어 다시 집는
	 * 간격이 영영 첫 값에 머문다.
	 */
	public void recordReconciled(LocalDateTime pickedAt) {
		this.lastReconcileAt = pickedAt;
		this.reconcileCount++;
	}

	/** 통지를 보낸 뒤에 남긴다. 먼저 남기면 전송이 실패했을 때 알린 것으로 남아 다시 알리지 않는다 */
	public void recordNotified(LocalDateTime notifiedAt) {
		this.lastNotifyAt = notifiedAt;
	}

	private void recordApproval(int approvedAmount, String pgTransactionId) {
		if (approvedAmount <= 0) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVED_AMOUNT_INVALID);
		}
		this.approvedAmount = approvedAmount;
		this.pgTransactionId = pgTransactionId;
	}

	private void close(PaymentCloseCode closeCode, String closeDetail) {
		this.closeCode = closeCode;
		this.closeDetail = closeDetail;
		changeStatus(closeCode.closedStatus());
	}

	/**
	 * 상태를 바꾸는 유일한 자리. 활성 슬롯은 상태가 정하므로 여기서 함께 맞춘다 — 슬롯만 바꾸는 길을
	 * 두면 상태와 어긋날 수 있다.
	 */
	private void changeStatus(PaymentStatus next) {
		this.status = next;
		this.activeOrderKey = next.holdsActiveSlot() ? this.orderId : null;
	}

	private void requireStatusIn(PaymentStatus... allowed) {
		if (Arrays.stream(allowed).noneMatch(candidate -> candidate == this.status)) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		}
	}

	private static void requireCloseCodeFor(PaymentCloseCode closeCode, PaymentStatus expected) {
		if (closeCode.closedStatus() != expected) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_CLOSE_CODE_NOT_ALLOWED);
		}
	}
}
