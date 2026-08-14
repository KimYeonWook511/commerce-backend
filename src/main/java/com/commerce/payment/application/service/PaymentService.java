package com.commerce.payment.application.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.payment.application.dto.RejectionAnomaly;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 도메인 안에서 끝나는 트랜잭션 단위작업. 환불을 만드는 것도 여기다 — 그것을 만들지 판정하는
 * 규칙이 결제 안에 있고 결제와 환불을 함께 저장해야 한다.
 *
 * <p>상태가 바뀐 사실을 남기는 로그가 여기 있다. 판정은 도메인이 하지만 그 판정을 커밋으로 확정하는
 * 것은 이 자리이고, 도메인 안에 로그를 두면 커밋되지 않은 전이까지 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

	private static final String PAYMENT_KEY_PREFIX = "PAY-";

	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;

	/**
	 * 앞 결제를 종결해 활성 슬롯을 비우고 새 결제가 그 자리를 잡는다.
	 *
	 * <p>둘이 한 트랜잭션인 것이 이 메서드의 이유다. 나누면 두 커밋 사이에 다른 요청이 슬롯을 잡을 수
	 * 있고, 앞엣것만 커밋되면 앞 결제는 종결됐는데 회원은 결제창을 못 받는 상태가 남는다.
	 */
	@Transactional
	public Payment start(Long orderId, Long memberId, PaymentPg pg, String idempotencyKey, int amount) {
		paymentRepository.findActiveByOrderId(orderId).ifPresent(this::yieldActiveSlot);
		return create(orderId, memberId, pg, idempotencyKey, amount);
	}

	/**
	 * 앞 결제에게 자리를 내주게 한다. 승인을 한 번도 부르지 않은 결제만 대상이다 — 승인을 부른 뒤의
	 * 결제가 슬롯을 반납하면 그 승인이 성공했을 때 한 주문에 승인이 둘 성립한다.
	 *
	 * <p>대상을 상태로 가른다. 회원이 결제창에서 인증을 마쳤어도 승인 요청이 우리에게 닿기 전이면 그
	 * 행은 여전히 승인 호출 전이고, 서버는 인증 여부를 알 수 없다.
	 */
	private void yieldActiveSlot(Payment active) {
		switch (active.getStatus()) {
			case READY -> {
				active.expire(PaymentCloseCode.SUPERSEDED);
				// 비우는 갱신이 새 결제의 삽입보다 먼저 DB에 나가야 한다. 코드 줄 순서로는 보장되지 않는다.
				paymentRepository.saveFlushed(active);
			}
			case IN_PROGRESS, UNKNOWN -> throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			case SUCCEEDED -> throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
			// 종결된 결제는 슬롯을 쥐지 않으므로 이 조회로 돌아올 수 없다.
			case FAILED, REJECTED, EXPIRED ->
				throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		}
	}

	/**
	 * 승인 호출 직전. 결제사 번호를 심고 부른 시각을 찍는다. 부르기 전에 따로 커밋하므로 응답을 못
	 * 받아도 그 결제를 가리키는 값이 우리 쪽에 남는다.
	 *
	 * <p>이 저장에 지면 부르는 쪽이 물러난다 — 진 것이 곧 "다른 쪽이 이미 집었다"는 뜻이다.
	 */
	@Transactional
	public Payment markInProgress(Long id, String pgPaymentId, LocalDateTime requestedAt) {
		Payment payment = load(id);
		payment.markInProgress(pgPaymentId, requestedAt);
		return paymentRepository.saveChecked(payment);
	}

	/**
	 * 승인을 다시 부르기 직전. 상태는 그대로 두고 부른 시각만 남긴다. 같은 키로 부를 때도 찍히므로
	 * 대사 유예가 그 값으로 다시 걸린다.
	 */
	@Transactional
	public Payment recordRequested(Long id, LocalDateTime requestedAt) {
		Payment payment = load(id);
		payment.recordRequested(requestedAt);
		return paymentRepository.saveChecked(payment);
	}

	/** 응답을 못 받아 승인 결과를 모른다 */
	@Transactional
	public void markUnknown(Long id) {
		Payment payment = load(id);
		payment.markUnknown();
		paymentRepository.saveChecked(payment);
		log.info("승인 결과 불명 paymentId={} orderId={}", id, payment.getOrderId());
	}

	/** 승인을 불렀는데 성립하지 않았다. 슬롯이 풀려 회원이 그 주문을 다시 결제할 수 있다 */
	@Transactional
	public void fail(Long id, PaymentCloseCode closeCode, String closeDetail) {
		Payment payment = load(id);
		payment.fail(closeCode, closeDetail);
		paymentRepository.saveChecked(payment);
		log.info("결제 실패 종결 paymentId={} orderId={} closeCode={}", id, payment.getOrderId(), closeCode);
	}

	/**
	 * 승인은 났으나 우리가 모르는 경로로 이미 취소됐다. 돈이 이미 돌아갔으므로 되돌릴 것이 없고,
	 * 승인이 났었다는 사실만 그 행에 남긴다.
	 */
	@Transactional
	public void failExternallyCanceled(Long id, String closeDetail, int approvedAmount, String pgTransactionId) {
		Payment payment = load(id);
		payment.failExternallyCanceled(closeDetail, approvedAmount, pgTransactionId);
		paymentRepository.saveChecked(payment);
		log.info("밖에서 취소된 승인으로 종결 paymentId={} orderId={} approvedAmount={}",
			id, payment.getOrderId(), approvedAmount);
	}

	/**
	 * 승인은 났는데 우리가 그 결제를 받아들일 수 없다. 되돌릴 환불을 만드는 것과 결제를 반려로
	 * 종결하는 것이 한 트랜잭션 한 저장이다 — 나누면 앞엣것만 커밋됐을 때 결제는 종착이라 아무도 다시
	 * 보지 않고 환불 기록도 없어, 나간 돈을 되돌릴 근거가 아무 데도 남지 않는다.
	 *
	 * <p>환불 생성이 조건 없이 먼저다. 결제 전이가 일어나지 않더라도 환불은 남아야 한다 — 돈은 이미
	 * 나갔다. 그리고 결제를 함께 저장하는 것이 동시 요청 방어다. 누적 환불액이 올라 결제 행이 바뀌므로,
	 * 둘이 각자 한도를 통과하더라도 진 쪽은 결제 버전에서 충돌하고 그 환불까지 함께 롤백된다.
	 *
	 * <p>다른 트랜잭션 서비스를 부르지 않고 리포지토리와 도메인 객체를 직접 다룬다. 환불을 "찾거나
	 * 만드는" 서비스를 따로 두면 혼자 불러도 되는 것처럼 생긴 문이 되어, 트랜잭션 없이 부르면 정당한
	 * 조건 없이 환불 의도만 커밋된다.
	 */
	@Transactional
	public RejectionAnomaly reject(
		Long id,
		PaymentCloseCode closeCode,
		String closeDetail,
		int approvedAmount,
		String pgTransactionId,
		RefundReason reason
	) {
		Payment payment = load(id);
		// 반려에는 밖에서 온 요청 키가 없어 요청자로 찾는다. 찾는 범위를 유일 제약과 같게 두어야
		// 조회가 못 찾은 것을 제약이 잡는 일이 정상 흐름에서 일어나지 않는다.
		Optional<Refund> existing = refundRepository.findSystemRefundByPaymentId(id);

		// 되돌릴 금액이 이 값에서 나오므로 환불을 열기 전에 담는다.
		payment.recordApproval(approvedAmount, pgTransactionId);
		Optional<Refund> refund = payment.openRejectionRefund(existing, reason);
		if (refund.isEmpty()) {
			// 만들 환불이 없으면 결제도 반려로 종결하지 않는다. 환불이 딸리지 않은 반려 행을 만들면
			// "반려된 결제에는 되돌릴 근거가 있다"는 대조가 통째로 무력해진다.
			log.error("남은 환불 한도가 0이라 반려 환불을 만들지 못했다 paymentId={} orderId={} approvedAmount={}",
				id, payment.getOrderId(), approvedAmount);
			return RejectionAnomaly.NO_REFUNDABLE_AMOUNT;
		}

		refundRepository.save(refund.get());
		boolean closed = payment.reject(closeCode, closeDetail);
		paymentRepository.saveChecked(payment);

		log.info("승인 반려 종결 paymentId={} orderId={} closeCode={} approvedAmount={} refundAmount={} closed={}",
			id, payment.getOrderId(), closeCode, approvedAmount, refund.get().getAmount(), closed);
		return closed ? RejectionAnomaly.NONE : RejectionAnomaly.PAYMENT_ALREADY_CLOSED;
	}

	/**
	 * 우리 결제를 종결하고, 승인 응답이 가리킨 결제 키의 주인 결제를 회수한다. 승인 응답의 결제 키가
	 * 우리 것이 아닐 때다 — 우리 결제에는 승인이 안 났고 나간 돈은 그 키 주인의 것이라 우리가 되돌릴
	 * 대상이 아니다.
	 *
	 * <p>둘이 한 트랜잭션인 것이 이 메서드의 이유다. 나누면 앞엣것만 커밋됐을 때 상대 결제가 아무도
	 * 보지 않는 상태로 남는다.
	 */
	@Transactional
	public void failAndReclaim(Long id, String counterpartPaymentKey, String pgPaymentId) {
		Payment ours = load(id);
		// 번호가 아니라 결제 키로 찾는다. 회수 대상 행에는 아직 번호가 없어(지금 심으려는 참이다)
		// 번호로 찾으면 늘 빈 결과가 나온다.
		Optional<Payment> counterpart = paymentRepository.findByPaymentKey(counterpartPaymentKey);

		ours.fail(PaymentCloseCode.PAYMENT_KEY_MISMATCH, "승인 응답의 결제 키가 다르다: " + counterpartPaymentKey);
		// 아무도 진행시키지 않는 행만 회수한다. 나머지 상태는 각자 자기 경로로 풀리며, 그대로 두면
		// 만료 배치가 종결해 슬롯을 반납하는데 돈은 나간 상태라 되돌릴 근거가 사라진다.
		counterpart.filter(target -> target.getStatus() == PaymentStatus.READY)
			.ifPresent(target -> target.reclaim(pgPaymentId));

		// 결제 키가 서로 엇갈린 두 요청이 겹치면 갱신이 반대 순서로 나가 서로를 기다린다. 늘 같은
		// 순서로 내보내 그 교착을 막는다.
		List<Payment> ordered = Stream.concat(Stream.of(ours), counterpart.stream())
			.sorted(Comparator.comparing(Payment::getId))
			.toList();
		ordered.forEach(paymentRepository::saveChecked);

		log.info("결제 키 불일치로 종결하고 상대 결제를 회수한다 paymentId={} counterpartPaymentKey={} reclaimed={}",
			id, counterpartPaymentKey,
			counterpart.filter(target -> target.getStatus() == PaymentStatus.UNKNOWN).isPresent());
	}

	/**
	 * 승인을 한 번도 부르지 않은 채 방치된 결제를 종결하고 슬롯을 반납한다. 실패로 종결하지 않는 것은
	 * 승인을 부른 적이 없어 성립하지 않은 승인이라는 것이 없기 때문이다.
	 *
	 * <p>종결 코드를 밖에서 받지 않는다. 자리를 내주는 종결과 이 배치가 하는 종결이 상태는 같아도 다른
	 * 일이고, 어느 쪽인지는 그것을 일으킨 자리가 안다.
	 */
	@Transactional
	public void expire(Long id) {
		Payment payment = load(id);
		payment.expire(PaymentCloseCode.SESSION_TIMEOUT);
		paymentRepository.saveChecked(payment);
		log.info("방치된 결제 만료 종결 paymentId={} orderId={}", id, payment.getOrderId());
	}

	/**
	 * 다시 시도할 수 있는 실패를 받았다. 상태는 그대로 두고 시도 번호만 올려 다음 호출이 새 키로 나가게
	 * 한다. 상태를 되돌리면 시간 조건 없이 집히는 자리로 가 실패가 돌아올 때마다 곧바로 다시 나간다.
	 */
	@Transactional
	public void recordRetryableFailure(Long id) {
		Payment payment = load(id);
		payment.recordRetryableFailure();
		paymentRepository.saveChecked(payment);
		log.info("다시 시도할 수 있는 실패로 시도 번호를 올린다 paymentId={} attemptSeq={}", id, payment.getAttemptSeq());
	}

	/**
	 * 대사가 이 건을 집었다. 결제사를 부르기 전에 따로 커밋한다 — 결과 반영과 한 트랜잭션으로 묶으면
	 * 호출이나 응답 처리가 깨졌을 때 집은 사실까지 롤백되어 회차가 오르지 않고, 다시 집는 간격이 첫 값에
	 * 머물러 장애가 길어질수록 결제사를 더 세게 두드린다.
	 *
	 * <p>이 저장에서 낙관 락이 걸린다. 두 주기가 같은 건을 동시에 집으면 진 쪽이 결제사를 부르기 전에
	 * 물러난다.
	 */
	@Transactional
	public Payment recordReconciled(Long id, LocalDateTime pickedAt) {
		Payment payment = load(id);
		payment.recordReconciled(pickedAt);
		return paymentRepository.saveChecked(payment);
	}

	/** 통지를 보낸 뒤에 남긴다. 먼저 남기면 전송이 실패했을 때 알린 것으로 남아 다시 알리지 않는다 */
	@Transactional
	public void recordNotified(Long id, LocalDateTime notifiedAt) {
		Payment payment = load(id);
		payment.recordNotified(notifiedAt);
		paymentRepository.saveChecked(payment);
	}

	/** 앞 결제가 없을 때의 생성. 결제사에 보낼 키를 새로 발급하고 그 자리에서 활성 슬롯을 잡는다 */
	private Payment create(Long orderId, Long memberId, PaymentPg pg, String idempotencyKey, int amount) {
		Payment payment = Payment.start(orderId, memberId, pg, generatePaymentKey(), idempotencyKey, amount);
		return paymentRepository.save(payment);
	}

	private String generatePaymentKey() {
		return PAYMENT_KEY_PREFIX + UlidGenerator.generate();
	}

	/**
	 * 단위작업은 내부 식별자로 다시 로드한다. 밖에서 온 값으로 한 건을 집는 조회는 소유 확인이 새지
	 * 않았는지 대조하는 기준이라 그 개수를 늘리지 않는다.
	 */
	private Payment load(Long id) {
		return paymentRepository.findById(id)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}
}
