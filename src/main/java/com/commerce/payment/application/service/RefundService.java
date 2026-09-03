package com.commerce.payment.application.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 환불 하나를 전이시키는 트랜잭션 단위작업. 성공 확정만 결제를 함께 로드해 갱신하고, 나머지 전이는
 * 환불만 로드한다.
 *
 * <p>나머지 전이가 결제 버전을 올리지 않는 것이 중요하다. 그 전이들은 한도도 한도의 사용 내역도 바꾸지
 * 않으므로 결제가 알 필요가 없고, 올리면 대사가 한 바퀴 돌 때마다 회원의 환불 요청이 낙관 락 충돌로
 * 밀린다. 확정은 실제로 돌아간 금액을 올리므로 결제가 알아야 하고, 환불 건당 한 번뿐이라 그 해악이
 * 되풀이되지 않는다.
 *
 * <p>상태가 바뀐 사실을 남기는 로그가 여기 있다. 판정은 도메인이 하지만 그 판정을 커밋으로 확정하는
 * 것은 이 자리이고, 도메인 안에 로그를 두면 커밋되지 않은 전이까지 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;

	/**
	 * 첫 발송의 결제사 호출 직전. 응답 대기로 옮기고 시도 번호를 올려 그 회차의 키를 만든다.
	 * 부르기 전에 따로 커밋하므로 부르는 도중 프로세스가 죽어도 언제까지 부르고 있었는지가 남는다.
	 *
	 * <p>이 저장에 지면 부르는 쪽이 물러난다 — 진 것이 곧 "다른 쪽이 이미 집었다"는 뜻이다.
	 */
	@Transactional
	public Refund markInProgress(Long id, LocalDateTime requestedAt) {
		Refund refund = load(id);
		refund.markInProgress(requestedAt);
		return refundRepository.saveChecked(refund);
	}

	/**
	 * 다시 부르기 직전. 상태를 그대로 두고 부른 시각만 남긴다. 같은 키로 부를 때도 찍히므로 대사 유예가
	 * 그 값으로 다시 걸린다.
	 */
	@Transactional
	public Refund recordRequested(Long id, LocalDateTime requestedAt) {
		Refund refund = load(id);
		refund.recordRequested(requestedAt);
		return refundRepository.saveChecked(refund);
	}

	/**
	 * 환불이 완료됐다. 자동으로 갈 수 있는 유일한 종착이며, 결제가 그 성공을 함께 받아들여 실제로
	 * 돌아간 금액이 오른다.
	 *
	 * <p>두 갱신이 한 트랜잭션이라 환불만 성공으로 남고 금액이 안 오르는 어긋남이 생기지 않는다. 금액
	 * 갱신이 환불의 상태 가드 안쪽이라 앞 확정이 커밋된 뒤에 다시 오는 확정도 두 번 계상되지 않는다.
	 */
	@Transactional
	public void complete(Long id, String pgTransactionId) {
		Refund refund = load(id);
		refund.complete(pgTransactionId);
		// 결제 갱신보다 먼저 나가야 한다. 두 행을 함께 저장하는 다른 자리들도 환불을 먼저 잡으므로,
		// 여기만 뒤집으면 서로 상대가 쥔 행을 기다리는 교착이 생긴다.
		refundRepository.saveChecked(refund);

		Payment payment = loadPayment(refund.getPaymentId());
		payment.recordRefundSuccess(refund.getAmount());
		paymentRepository.saveChecked(payment);

		// 결제 갱신이 성공한 뒤에 남긴다. 앞에 두면 결제 행 경합으로 되돌려진 확정에도 "환불 완료"가
		// 남아, 돈이 실제로 확정됐는지를 로그로 되짚을 수 없다.
		log.info("환불 완료 refundId={} paymentId={} amount={} refundSucceededAmount={}",
			id, refund.getPaymentId(), refund.getAmount(), payment.getRefundSucceededAmount());
	}

	/** 응답을 못 받아 결과를 모른다 */
	@Transactional
	public void markUnknown(Long id) {
		Refund refund = load(id);
		refund.markUnknown();
		refundRepository.saveChecked(refund);
		log.info("환불 결과 불명 refundId={} paymentId={}", id, refund.getPaymentId());
	}

	/** 자동으로는 더 진행할 수 없다. 종결이 아니라 사람이 이어받아야 한다는 뜻이다 */
	@Transactional
	public void flagForReview(Long id, RefundReviewCode reviewCode, String reviewDetail) {
		Refund refund = load(id);
		refund.flagForReview(reviewCode, reviewDetail);
		refundRepository.saveChecked(refund);
		log.warn("환불을 사람이 처리해야 한다 refundId={} paymentId={} reviewCode={}",
			id, refund.getPaymentId(), reviewCode);
	}

	/**
	 * 다시 시도할 수 있는 실패를 받았다. 상태는 그대로 두고 새 시도를 열어 다음 호출이 새 키로 나가게
	 * 한다. 상태를 되돌리면 시간 조건 없이 집히는 자리로 가 실패가 돌아올 때마다 곧바로 다시 나간다.
	 */
	@Transactional
	public void recordRetryableFailure(Long id) {
		Refund refund = load(id);
		refund.recordRetryableFailure();
		refundRepository.saveChecked(refund);
		log.info("다시 시도할 수 있는 실패로 환불 시도 번호를 올린다 refundId={} attemptSeq={}",
			id, refund.getAttemptSeq());
	}

	/**
	 * 대사가 이 건을 집었다. 확정했든 못 했든, 그 자리에서 다시 불렀든 회차가 오른다.
	 *
	 * <p>결제사를 부르기 전에 따로 커밋한다. 결과 반영과 한 트랜잭션으로 묶으면 호출이나 응답 처리가
	 * 깨졌을 때 집은 사실까지 함께 롤백되어 회차가 오르지 않고, 그러면 다시 집는 간격이 첫 값에 머물러
	 * 장애가 길어질수록 결제사를 더 세게 두드린다.
	 *
	 * <p>두 방어가 서로 다른 창을 막는다. 앞선 주기의 갱신이 아직 커밋 전이면 이 저장에서 낙관 락이
	 * 걸리고, 이미 커밋됐으면 집을 때 본 회차와 달라져 도메인이 거른다 — 다시 읽는 이 자리는 그 커밋된
	 * 값을 그대로 보므로 버전이 어긋나지 않는다.
	 *
	 * @return 집은 환불. 다른 주기가 이미 집었으면 비어 있다
	 */
	@Transactional
	public Optional<Refund> recordReconciled(Long id, int expectedReconcileCount, LocalDateTime pickedAt) {
		Refund refund = load(id);
		if (!refund.recordReconciled(expectedReconcileCount, pickedAt)) {
			return Optional.empty();
		}
		return Optional.of(refundRepository.saveChecked(refund));
	}

	/** 통지를 보낸 뒤에 남긴다. 먼저 남기면 전송이 실패했을 때 알린 것으로 남아 다시 알리지 않는다 */
	@Transactional
	public void recordNotified(Long id, LocalDateTime notifiedAt) {
		Refund refund = load(id);
		refund.recordNotified(notifiedAt);
		refundRepository.saveChecked(refund);
	}

	/**
	 * 단위작업은 내부 식별자로 다시 로드한다. 밖에서 온 값으로 한 건을 집는 조회는 소유 확인이 새지
	 * 않았는지 대조하는 기준이라 그 개수를 늘리지 않는다.
	 */
	private Refund load(Long id) {
		return refundRepository.findById(id)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	/**
	 * 부르는 흐름이 이 결제를 이미 읽어 두고 들어오므로 여기서 못 찾는 일이 없다. 결제 행이 정말
	 * 없으면 그 앞 조회에서 먼저 걸린다.
	 */
	private Payment loadPayment(Long paymentId) {
		return paymentRepository.findById(paymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}
}
