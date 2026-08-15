package com.commerce.payment.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 환불 하나를 전이시키는 트랜잭션 단위작업. 환불만 로드하고 결제를 건드리지 않는다.
 *
 * <p>결제 버전을 올리지 않는 것이 중요하다. 상태 전이는 한도를 바꾸지 않으므로 결제가 알 필요가 없고,
 * 올리면 대사가 한 바퀴 돌 때마다 회원의 환불 요청이 낙관 락 충돌로 밀린다. 결제를 함께 저장하는 것은
 * 환불을 만들 때뿐이다.
 *
 * <p>상태가 바뀐 사실을 남기는 로그가 여기 있다. 판정은 도메인이 하지만 그 판정을 커밋으로 확정하는
 * 것은 이 자리이고, 도메인 안에 로그를 두면 커밋되지 않은 전이까지 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

	private final RefundRepository refundRepository;

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

	/** 환불이 완료됐다. 자동으로 갈 수 있는 유일한 종착이다 */
	@Transactional
	public void complete(Long id, String pgTransactionId) {
		Refund refund = load(id);
		refund.complete(pgTransactionId);
		refundRepository.saveChecked(refund);
		log.info("환불 완료 refundId={} paymentId={} amount={}", id, refund.getPaymentId(), refund.getAmount());
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
	 * 단위작업은 내부 식별자로 다시 로드한다. 밖에서 온 값으로 한 건을 집는 조회는 소유 확인이 새지
	 * 않았는지 대조하는 기준이라 그 개수를 늘리지 않는다.
	 */
	private Refund load(Long id) {
		return refundRepository.findById(id)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}
}
