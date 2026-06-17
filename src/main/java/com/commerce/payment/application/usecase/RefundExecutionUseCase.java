package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.application.service.FailCancelPaymentService;
import com.commerce.payment.application.service.MarkUnknownCancelPaymentService;
import com.commerce.payment.application.service.SucceedCancelPaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 주도 환불 실행 경로 (ADR-L2, ADR-L3).
 * 영속된 CANCEL 결제(REQUESTED)에 대해 PG 취소를 호출하고 결과를 반영한다.
 * tx 밖(커밋 이후)에서 호출되는 best-effort 경로이며, approve 결제를 건드리지 않는다.
 * CANCEL 대사(step2)의 재실행과 같은 구조를 공유하도록 CANCEL 결제 객체를 직접 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundExecutionUseCase {

	// transition(별도 빈)이 던지는 도메인 예외 중 best-effort에서 skip할 코드 집합.
	// 충돌(다른 주체가 먼저 종착) / 가드 위반(이미 종착) / 이력 없음은 단조 종착이므로 흡수한다. 흡수는 트랜잭션 경계 밖(useCase)에서만 한다.
	private static final Set<PaymentErrorCode> SKIPPABLE = EnumSet.of(
		PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED,
		PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED,
		PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND
	);

	private final SucceedCancelPaymentService succeedCancelPaymentService;
	private final FailCancelPaymentService failCancelPaymentService;
	private final MarkUnknownCancelPaymentService markUnknownCancelPaymentService;

	/**
	 * 주어진 CANCEL 결제에 대해 PG 취소를 호출하고 결과를 반영한다.
	 * REQUESTED가 아닌 CANCEL 결제는 이미 종착/처리됐으므로 아무 것도 하지 않는다(멱등).
	 *
	 * @param cancelPayment 환불 의도로 영속된 CANCEL 결제
	 * @param pgCanceller   PG 취소 호출 콜백
	 */
	public void execute(Payment cancelPayment, PgCanceller pgCanceller) {
		if (cancelPayment.getStatus() != PaymentStatus.REQUESTED) {
			return;
		}

		LocalDateTime now = LocalDateTime.now();
		CancelOutcome outcome;
		try {
			outcome = pgCanceller.cancel(cancelPayment, "사용자 주도 환불");
		} catch (Exception ex) {
			log.warn("환불 PG 취소 호출 실패 merchantPayKey={} pgPaymentId={}",
				cancelPayment.getMerchantPayKey(), cancelPayment.getPgPaymentId(), ex);
			markUnknownSkippable(cancelPayment, "PG 취소 호출 실패: " + ex.getMessage(), now);
			return;
		}

		switch (outcome.status()) {
			case SUCCESS -> succeedSkippable(cancelPayment, now);
			case PROCESSING -> {} // no-op
			case FAILED -> failSkippable(cancelPayment, outcome, now);
			case UNKNOWN -> markUnknownSkippable(cancelPayment, outcome.failDetail(), now);
		}
	}

	private void succeedSkippable(Payment cancelPayment, LocalDateTime respondedAt) {
		try {
			succeedCancelPaymentService.succeed(
				cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
				cancelPayment.getPgPaymentId(), respondedAt
			);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("환불 CANCEL 성공 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), cancelPayment.getMerchantPayKey(), cancelPayment.getPgPaymentId());
				return;
			}
			throw ex;
		}
	}

	private void failSkippable(Payment cancelPayment, CancelOutcome outcome, LocalDateTime respondedAt) {
		try {
			failCancelPaymentService.fail(
				cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
				cancelPayment.getPgPaymentId(), outcome.failCode(), outcome.failDetail(), respondedAt
			);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("환불 CANCEL 실패 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), cancelPayment.getMerchantPayKey(), cancelPayment.getPgPaymentId());
				return;
			}
			throw ex;
		}
	}

	private void markUnknownSkippable(Payment cancelPayment, String failDetail, LocalDateTime respondedAt) {
		try {
			markUnknownCancelPaymentService.markUnknown(
				cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
				cancelPayment.getPgPaymentId(), failDetail, respondedAt
			);
		} catch (PaymentException ex) {
			if (SKIPPABLE.contains(ex.getErrorCode())) {
				log.warn("환불 CANCEL UNKNOWN 마킹 skip - {} merchantPayKey={} pgPaymentId={}",
					ex.getErrorCode(), cancelPayment.getMerchantPayKey(), cancelPayment.getPgPaymentId());
				return;
			}
			throw ex;
		}
	}
}
