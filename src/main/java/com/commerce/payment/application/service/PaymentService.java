package com.commerce.payment.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 결제 도메인 안에서 끝나는 트랜잭션 단위작업.
 */
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

	/** 앞 결제가 없을 때의 생성. 결제사에 보낼 키를 새로 발급하고 그 자리에서 활성 슬롯을 잡는다 */
	private Payment create(Long orderId, Long memberId, PaymentPg pg, String idempotencyKey, int amount) {
		Payment payment = Payment.start(orderId, memberId, pg, generatePaymentKey(), idempotencyKey, amount);
		return paymentRepository.save(payment);
	}

	private String generatePaymentKey() {
		return PAYMENT_KEY_PREFIX + UlidGenerator.generate();
	}
}
