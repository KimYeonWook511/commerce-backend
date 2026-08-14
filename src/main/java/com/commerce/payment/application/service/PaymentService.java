package com.commerce.payment.application.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 도메인 안에서 끝나는 트랜잭션 단위작업.
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

	/** 승인은 났는데 우리가 그 결제를 받아들일 수 없다. 되돌릴 금액을 알아야 하므로 승인 금액을 남긴다 */
	@Transactional
	public void reject(
		Long id,
		PaymentCloseCode closeCode,
		String closeDetail,
		int approvedAmount,
		String pgTransactionId
	) {
		Payment payment = load(id);
		payment.reject(closeCode, closeDetail, approvedAmount, pgTransactionId);
		paymentRepository.saveChecked(payment);
		log.info("승인 반려 종결 paymentId={} orderId={} closeCode={} approvedAmount={}",
			id, payment.getOrderId(), closeCode, approvedAmount);
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
