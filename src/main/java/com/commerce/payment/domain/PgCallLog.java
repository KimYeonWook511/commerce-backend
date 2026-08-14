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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 결제사를 부른 사실 하나. 한 행이 한 호출이고, 승인 호출과 환불 호출을 한 곳에 담는다.
 *
 * <p>결제·환불 어느 aggregate에도 들지 않는다. 어떤 불변식에도 참여하지 않고 판정에도 쓰이지 않으므로,
 * 거래 행을 통해 저장하면 순수한 기록 남기기가 낙관 락 충돌을 일으킨다. 그래서 자기 리포지토리로
 * 저장하고 낙관 락을 갖지 않으며, 결제 쪽이 롤백돼도 "불렀다"는 사실이 남도록 별도 트랜잭션으로
 * 커밋한다.
 *
 * <p>호출 직전에 행을 만들고 결과를 받으면 그 행을 한 번 채운다. 응답을 받은 뒤에 만들면 응답을 못
 * 받았을 때 행이 아예 안 생겨, 결제사에 요청이 갔는지를 영영 알 수 없다 — 그때가 바로 조사가 필요한
 * 경우다. 재시도는 새 행이다.
 */
@Entity
@Table(
	name = "tbl_pg_call_log",
	indexes = {
		@Index(name = "idx_pg_call_log_payment_requested", columnList = "payment_id, requested_at"),
		@Index(name = "idx_pg_call_log_refund", columnList = "refund_id"),
		@Index(name = "idx_pg_call_log_type_requested", columnList = "call_type, requested_at")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class PgCallLog extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 환불 호출도 결제에 딸리므로 항상 채운다 */
	@Column(name = "payment_id", nullable = false)
	private Long paymentId;

	/** 환불 호출일 때만 채운다 */
	@Column(name = "refund_id")
	private Long refundId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "call_type", nullable = false)
	private PgCallType callType;

	/**
	 * 그때 결제사에 실제로 보낸 멱등키. 결과를 모르는 동안은 같은 키로 다시 부르므로 같은 값이 여러
	 * 행에 반복될 수 있고, 여기서 같은 값을 여러 번 보는 것이 정상이다.
	 */
	@Column(name = "pg_idempotency_key", nullable = false, length = 64)
	private String pgIdempotencyKey;

	/** 부른 시각. 행을 만들 때 채우고 그 뒤로 바뀌지 않는다 */
	@Column(name = "requested_at", nullable = false)
	private LocalDateTime requestedAt;

	/** 응답을 받은 시각. 비어 있으면 그 호출의 결과를 모른다 */
	@Column(name = "responded_at")
	private LocalDateTime respondedAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "error_type")
	private PgErrorType errorType;

	@Column(name = "result_code", length = 64)
	private String resultCode;

	/** 중복 요청을 뜻하는 상태 코드를 구분하기 위해 남긴다 */
	@Column(name = "http_status")
	private Integer httpStatus;

	/** 응답 원본 그대로. 뽑아낸 값만 담으면 나중에 무슨 일이 있었는지 되짚을 수 없다 */
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "raw_response")
	private String rawResponse;

	private PgCallLog(
		Long paymentId,
		Long refundId,
		PgCallType callType,
		String pgIdempotencyKey,
		LocalDateTime requestedAt
	) {
		this.paymentId = paymentId;
		this.refundId = refundId;
		this.callType = callType;
		this.pgIdempotencyKey = pgIdempotencyKey;
		this.requestedAt = requestedAt;
	}

	/** 승인 호출을 부르기 직전에 남긴다. 환불 참조는 비운다 */
	public static PgCallLog startApproveCall(Long paymentId, String pgIdempotencyKey, LocalDateTime requestedAt) {
		return new PgCallLog(paymentId, null, PgCallType.APPROVE, pgIdempotencyKey, requestedAt);
	}

	/** 환불 호출을 부르기 직전에 남긴다. 한 결제의 경위를 한 번에 볼 수 있도록 결제 참조도 함께 담는다 */
	public static PgCallLog startRefundCall(
		Long paymentId,
		Long refundId,
		String pgIdempotencyKey,
		LocalDateTime requestedAt
	) {
		return new PgCallLog(paymentId, refundId, PgCallType.REFUND, pgIdempotencyKey, requestedAt);
	}

	/**
	 * 그 호출의 결과를 한 번 채운다. 그 뒤로는 고치지 않고 지우지 않는다.
	 * 응답을 못 받은 호출은 응답 시각이 비고 무슨 일이 있었는지는 실패 유형만이 말한다.
	 */
	public void recordResult(
		LocalDateTime respondedAt,
		PgErrorType errorType,
		String resultCode,
		Integer httpStatus,
		String rawResponse
	) {
		this.respondedAt = respondedAt;
		this.errorType = errorType;
		this.resultCode = resultCode;
		this.httpStatus = httpStatus;
		this.rawResponse = rawResponse;
	}
}
