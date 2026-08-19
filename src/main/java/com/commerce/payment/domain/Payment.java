package com.commerce.payment.domain;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

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

	/** 결제사 호출 멱등키를 결제 키에서 파생할 때 쓰는 구분자 */
	private static final String ATTEMPT_SEPARATOR = "-";

	/**
	 * 환불 사건 키 접두어. 시도 번호를 붙인 값이 결제사 한도를 넘지 못하도록 짧게 두고, 뒤에는 붙임표를
	 * 뺀 UUID를 붙여 35자로 고정한다 — 길이를 정해 두지 않으면 파생값이 한도에서 잘려 서로 다른 시도가
	 * 한 키로 접힌다.
	 */
	private static final String REFUND_KEY_PREFIX = "RF-";

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

	/**
	 * 같은 멱등키로 다시 온 요청이 이 결제를 만든 그 요청과 같은지 확인한다. 다르면 이 결제를 돌려주지
	 * 않고 거절한다 — 다른 주문을 결제하려던 요청에 앞 주문의 결제창 값이 나가면 회원이 그 창에서
	 * 인증해 엉뚱한 주문이 결제되고, 승인까지 성공하므로 어떤 실패 응답도 나가지 않는다.
	 */
	public void requireSameRequest(Long orderId, int amount) {
		if (!this.orderId.equals(orderId) || this.amount != amount) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_IDEMPOTENCY_KEY_CONFLICT);
		}
	}

	/** 승인 호출 직전. 결제사 번호를 심고 첫 호출의 시도 번호를 올린다. 부르기 전에 따로 커밋한다 */
	public void markInProgress(String pgPaymentId, LocalDateTime requestedAt) {
		requireStatusIn(PaymentStatus.READY);
		this.pgPaymentId = pgPaymentId;
		this.attemptSeq++;
		this.lastRequestedAt = requestedAt;
		changeStatus(PaymentStatus.IN_PROGRESS);
	}

	/**
	 * 승인을 다시 부르기 직전. 상태를 그대로 두고 부른 시각만 남긴다.
	 *
	 * <p>같은 키로 다시 불러도 찍는다 — 대사 유예를 이 값으로 재므로, 안 찍으면 방금 부른 건이 아직
	 * 부르는 중인 건과 구분되지 않아 다음 주기가 곧바로 겹쳐 부른다.
	 *
	 * <p>시도 번호는 여기서 올리지 않는다. 결과를 모르는 동안은 같은 키를 그대로 실어 결제사가 저장해
	 * 둔 응답을 되돌려주게 하고, 다시 시도할 수 있는 실패라는 것을 알게 된 자리에서만 새 키가 된다.
	 */
	public void recordRequested(LocalDateTime requestedAt) {
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		this.lastRequestedAt = requestedAt;
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
	 * 승인은 났는데 우리가 그 결제를 받아들일 수 없어 반려로 종결한다. 되돌릴 환불을 먼저 연 자리에서
	 * 부르므로 승인 금액은 이미 담겨 있다. 환불 생성은 이 메서드가 하지 않는다 — 결제를 종결하는 것과
	 * 별개의 일이다.
	 *
	 * <p>이미 종착이면 전이를 건너뛰고 그 사실을 돌려준다. 전이가 안 됐다고 되돌릴 근거까지 롤백하면
	 * 이미 나간 돈이 그대로 남기 때문이다. 반려하는 시점에 종착이 아닌 것이 정상이므로 그 조합은
	 * 어딘가 어긋났다는 신호이며, 알리는 것은 커밋 뒤를 아는 호출자가 한다.
	 *
	 * @return 전이가 일어났으면 true
	 */
	public boolean reject(PaymentCloseCode closeCode, String closeDetail) {
		requireCloseCodeFor(closeCode, PaymentStatus.REJECTED);
		if (this.status.isTerminal()) {
			return false;
		}
		requireStatusIn(PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN);
		close(closeCode, closeDetail);
		return true;
	}

	/**
	 * 회원 요청으로 환불을 연다. 같은 요청 키의 사건이 이미 있으면 그것을 그대로 돌려주고 누적 환불액을
	 * 다시 더하지 않는다. 없으면 한도를 판정해 새로 만들고 그 금액만큼 누적 환불액을 더한다.
	 *
	 * <p>넘겨받은 사건이 이 결제의 것인지 먼저 대조한다 — 조회를 한 번 잘못 좁히면 남의 환불이 이번
	 * 요청의 결과로 돌아간다.
	 */
	public Refund openRefund(Optional<Refund> existing, int amount, RefundReason reason, String idempotencyKey) {
		if (existing.isPresent()) {
			Refund found = requireOwnRefund(existing.get());
			found.requireSameRequest(amount, reason);
			return found;
		}
		return addRefund(RefundRequester.MEMBER, idempotencyKey, amount, reason);
	}

	/**
	 * 승인 반려로 환불을 연다. 금액을 받지 않고 그 시점의 남은 한도(승인 금액 − 누적 환불액)를 스스로
	 * 계산한다 — 상태에서 파생되는 판단이라 밖에서 계산해 넘기면 호출자가 도메인 규칙을 들게 된다.
	 * 승인 금액으로 고정하지도 않는다. 정상 경로에서는 둘이 같고, 다른 순간은 이미 어긋난 상태라
	 * 고정값을 쓰면 한도를 넘어 반려가 통째로 막힌다.
	 *
	 * <p>반려는 결제당 한 건이라 시스템이 요청한 환불이 이미 있으면 금액을 다시 계산하지 않고 그것을
	 * 돌려준다. 계산하면 그 환불이 이미 한도를 잡고 있어 남은 한도가 0이 되고, 저장된 금액과 달라
	 * 내용 불일치로 거절된다.
	 *
	 * <p>남은 한도가 0이면 비어 있는 결과를 돌려준다. 예외로 터뜨리면 반려가 통째로 롤백되어 이미 나간
	 * 돈을 되돌릴 근거가 사라진다. 호출자는 그것이 비었는지만 보고 저장 여부를 가른다.
	 */
	public Optional<Refund> openRejectionRefund(Optional<Refund> existing, RefundReason reason) {
		if (existing.isPresent()) {
			return Optional.of(requireOwnRefund(existing.get()));
		}
		int remaining = remainingRefundableAmount();
		if (remaining <= 0) {
			return Optional.empty();
		}
		// 요청 멱등키에 사유 값을 담는다. 비워 두면 유일 검사에서 빠져 DB가 중복을 막지 못하고, 고정
		// 문자열로 두면 사유가 늘었을 때 두 번째 종류가 제약에 막혀 조용히 안 만들어진다.
		return Optional.of(addRefund(RefundRequester.SYSTEM, reason.name(), remaining, reason));
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

	/**
	 * 승인 호출에 실을 결제사 호출 멱등키. 결제 키에 시도 번호를 붙여 파생하므로 시도 번호가 오르면
	 * 함께 새것이 되고, 결과를 모르는 동안 다시 부를 때는 같은 값이 그대로 나간다 — 같은 시도를 다시
	 * 묻는 것이지 새 요청이 아니라는 표시다.
	 *
	 * <p>이 값으로 앞선 호출의 결과를 알아내지는 않는다. 같은 키에 이전 응답을 되돌려주는지는 결제사와
	 * 부르는 API가 정하는 것이고 승인에서는 오지 않는다. 승인이 이미 났는지는 결제사가 그 사실을 알려주는
	 * 응답 코드로 갈리고, 얼마가 승인됐는지는 이력을 읽어 정한다.
	 *
	 * <p>파생 규칙을 이 자리 하나에 두는 것은 보내는 값과 기록에 남기는 값이 갈리면 어느 호출이 어느
	 * 시도였는지를 되짚을 수 없기 때문이다.
	 */
	public String pgIdempotencyKey() {
		return paymentKey + ATTEMPT_SEPARATOR + attemptSeq;
	}

	/**
	 * 결제사가 승인한 사실을 결제 행에 남긴다. 되돌릴 금액이 이 값에서 나오므로 반려가 환불을 열기
	 * 전에 먼저 담는다 — 결제가 이미 종착이라 전이를 건너뛰는 경로에서도 이 값이 있어야 얼마를
	 * 돌려줄지 정해진다.
	 */
	public void recordApproval(int approvedAmount, String pgTransactionId) {
		if (approvedAmount <= 0) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_APPROVED_AMOUNT_INVALID);
		}
		this.approvedAmount = approvedAmount;
		this.pgTransactionId = pgTransactionId;
	}

	/**
	 * 환불 하나를 더한다. 한도 판정과 누적 환불액 갱신이 한 자리에 있어야 둘이 갈리지 않는다.
	 *
	 * <p>누적 환불액을 더하는 것이 곧 동시 요청 방어다. 결제 행이 실제로 바뀌어야 갱신 질의가 나가고
	 * 버전이 오르며, 각자 한도를 통과한 두 요청 중 진 쪽이 그 버전에서 충돌해 자기 환불까지 함께
	 * 롤백된다. 이 한 줄이 빠지면 겉보기에는 잘 돌다가 경합에서만 한도를 넘는다.
	 */
	private Refund addRefund(RefundRequester requester, String idempotencyKey, int amount, RefundReason reason) {
		if (amount > remainingRefundableAmount()) {
			throw new PaymentException(PaymentErrorCode.REFUND_LIMIT_EXCEEDED);
		}
		// 금액이 0보다 큰지와 요청 키가 있는지는 환불의 생성 관문이 지킨다. 여기서 값을 조립해 만들면
		// 관문이 둘로 갈린다.
		Refund refund = Refund.open(this.id, generateRefundKey(), requester, idempotencyKey, amount, reason);
		this.totalRefundedAmount += amount;
		return refund;
	}

	/**
	 * 남은 한도. 모든 상태의 환불이 이미 누적 환불액에 들어 있어 상태로 예외를 두지 않는다 — 결과를
	 * 모르는 것도 자동 처리가 멈춘 것도 아직 돈이 돌아가지 않았고, 그 몫을 풀어 주면 새 환불이 끼어든다.
	 *
	 * <p>주문 취소 응답이 "앞으로 더 취소할 수 있는 금액"으로 이 값을 그대로 싣는다. 한도를 재는 것과
	 * 응답이 말하는 것이 같은 계산이어야 한도는 통과하는데 응답이 0을 말하는 어긋남이 생기지 않는다.
	 */
	public int remainingRefundableAmount() {
		if (approvedAmount == null) {
			// 얼마를 돌려줘야 하는지가 정해지지 않았다.
			throw new PaymentException(PaymentErrorCode.REFUND_APPROVED_AMOUNT_MISSING);
		}
		return approvedAmount - totalRefundedAmount;
	}

	private Refund requireOwnRefund(Refund refund) {
		if (!refund.getPaymentId().equals(this.id)) {
			throw new PaymentException(PaymentErrorCode.REFUND_NOT_OWNED_BY_PAYMENT);
		}
		return refund;
	}

	/** 환불 사건 키는 결제사를 부르기 전에 정해져야 하므로 환불을 만드는 이 자리에서 발급한다 */
	private static String generateRefundKey() {
		return REFUND_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "");
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
