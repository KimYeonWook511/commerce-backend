package com.commerce.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.order.application.port.BlockingPaymentChecker;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.support.TestcontainersSupport;

/**
 * 주문 만료 배치가 무엇을 건너뛰는지는 활성 슬롯으로 판정한다. 결제창을 띄운 것부터 결과를 모르는
 * 것까지 전부 막힌 것으로 보는지가 이 판정의 핵심이다.
 */
@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, BlockingPaymentCheckerAdapter.class, PaymentRepositoryAdapter.class})
class BlockingPaymentCheckerAdapterIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private BlockingPaymentChecker blockingPaymentChecker;

	@Autowired
	private PaymentRepository paymentRepository;

	private static final Long MEMBER_ID = 700L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	// 주문·회원은 식별자로만 참조하고 외래 키가 없으므로 실제 행 없이 가상 식별자를 쓴다.
	private static long nextOrderId = 7000L;

	private Payment startedPayment(long orderId, String suffix) {
		return Payment.start(orderId, MEMBER_ID, PaymentPg.NAVERPAY, "PK-" + suffix, "IDEM-" + suffix, 10_000);
	}

	@DisplayName("결제창만 띄운 주문도 만료 차단 대상이다 — 인증하는 사이 주문이 사라지면 돌아온 승인을 받을 자리가 없다")
	@Test
	void findOrderIdsWithBlockingPayment_includesOrderWaitingForApprovalCall() {
		long orderId = ++nextOrderId;
		paymentRepository.save(startedPayment(orderId, "ready"));

		Set<Long> blocked = blockingPaymentChecker.findOrderIdsWithBlockingPayment(List.of(orderId));

		assertThat(blocked).containsExactly(orderId);
	}

	@DisplayName("승인을 호출했거나 결과를 모르는 결제가 걸린 주문도 만료 차단 대상이다")
	@Test
	void findOrderIdsWithBlockingPayment_includesCalledAndUnknownPayments() {
		long inProgressOrderId = ++nextOrderId;
		Payment inProgress = startedPayment(inProgressOrderId, "in-progress");
		inProgress.markInProgress("pg-in-progress", NOW);
		paymentRepository.save(inProgress);

		long unknownOrderId = ++nextOrderId;
		Payment unknown = startedPayment(unknownOrderId, "unknown");
		unknown.markInProgress("pg-unknown", NOW);
		unknown.markUnknown();
		paymentRepository.save(unknown);

		Set<Long> blocked = blockingPaymentChecker.findOrderIdsWithBlockingPayment(
			List.of(inProgressOrderId, unknownOrderId));

		assertThat(blocked).containsExactlyInAnyOrder(inProgressOrderId, unknownOrderId);
	}

	@DisplayName("성공한 결제가 걸린 주문은 슬롯을 계속 쥐고 있어 만료 차단 대상이다")
	@Test
	void findOrderIdsWithBlockingPayment_includesSucceededPayment() {
		long orderId = ++nextOrderId;
		Payment succeeded = startedPayment(orderId, "succeeded");
		succeeded.markInProgress("pg-succeeded", NOW);
		succeeded.succeed(10_000, "pg-tx-1");
		paymentRepository.save(succeeded);

		Set<Long> blocked = blockingPaymentChecker.findOrderIdsWithBlockingPayment(List.of(orderId));

		assertThat(blocked).containsExactly(orderId);
	}

	@DisplayName("슬롯을 반납한 결제만 있는 주문은 다시 만료 대상이 된다")
	@Test
	void findOrderIdsWithBlockingPayment_excludesOrdersWhoseSlotWasReleased() {
		long expiredOrderId = ++nextOrderId;
		Payment expired = startedPayment(expiredOrderId, "expired");
		expired.expire(PaymentCloseCode.SESSION_TIMEOUT);
		paymentRepository.save(expired);

		long failedOrderId = ++nextOrderId;
		Payment failed = startedPayment(failedOrderId, "failed");
		failed.markInProgress("pg-failed", NOW);
		failed.fail(PaymentCloseCode.PG_DECLINED, "거절");
		paymentRepository.save(failed);

		Set<Long> blocked = blockingPaymentChecker.findOrderIdsWithBlockingPayment(
			List.of(expiredOrderId, failedOrderId));

		assertThat(blocked).isEmpty();
	}
}
