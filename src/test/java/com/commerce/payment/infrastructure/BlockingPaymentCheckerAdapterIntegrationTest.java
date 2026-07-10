package com.commerce.payment.infrastructure;

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
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.BlockingPaymentCheckerAdapter;
import com.commerce.payment.infrastructure.persistence.PaymentRepositoryAdapter;
import com.commerce.support.TestcontainersSupport;

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
	private BlockingPaymentCheckerAdapter blockingPaymentChecker;

	@Autowired
	private PaymentRepository paymentRepository;

	// tbl_payment.order_id 는 cross-aggregate 참조를 ID로만 하는 설계라 FK 없는 단순 컬럼이므로, 실제 주문 없이 가상 ID를 사용한다.
	private static long nextOrderId = 8000L;

	private PaymentReservation reservation(long orderId, String merchantPayKey) {
		return PaymentReservation.createReserved(
			orderId, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey,
			LocalDateTime.now().plusMinutes(15));
	}

	@DisplayName("미확정 REQUESTED 결제(승인 호출 후 결과 저장 전 중단되어 과금됐을 수 있음)가 걸린 주문은 만료 차단 대상에 포함된다")
	@Test
	void blockingOrderIds_includesRequested() {
		long orderId = ++nextOrderId;
		Payment requested = Payment.createRequested(reservation(orderId, "PAY-BP-1"), PaymentType.APPROVE, "pg-bp-1");
		paymentRepository.save(requested);

		Set<Long> result = blockingPaymentChecker.findOrderIdsWithBlockingPayment(List.of(orderId));

		assertThat(result).containsExactly(orderId);
	}

	@DisplayName("결과 불명 UNKNOWN 결제가 걸린 주문은 만료 차단 대상에 포함된다")
	@Test
	void blockingOrderIds_includesUnknown() {
		long orderId = ++nextOrderId;
		Payment unknown = Payment.createRequested(reservation(orderId, "PAY-BP-2"), PaymentType.APPROVE, "pg-bp-2");
		unknown.markUnknown("timeout", LocalDateTime.now());
		paymentRepository.save(unknown);

		Set<Long> result = blockingPaymentChecker.findOrderIdsWithBlockingPayment(List.of(orderId));

		assertThat(result).containsExactly(orderId);
	}

	@DisplayName("SUCCEEDED, FAILED 등 종결 상태 결제만 있는 주문은 만료 차단 대상에서 제외된다")
	@Test
	void blockingOrderIds_excludesTerminal() {
		long succeededOrderId = ++nextOrderId;
		Payment succeeded = Payment.createRequested(reservation(succeededOrderId, "PAY-BP-3-S"), PaymentType.APPROVE, "pg-bp-3-s");
		succeeded.succeed(LocalDateTime.now());
		paymentRepository.save(succeeded);

		long failedOrderId = ++nextOrderId;
		Payment failed = Payment.createRequested(reservation(failedOrderId, "PAY-BP-3-F"), PaymentType.APPROVE, "pg-bp-3-f");
		failed.fail(PaymentFailCode.PG_REQUEST_REJECTED, "rejected", LocalDateTime.now());
		paymentRepository.save(failed);

		Set<Long> result = blockingPaymentChecker.findOrderIdsWithBlockingPayment(
			List.of(succeededOrderId, failedOrderId));

		assertThat(result).isEmpty();
	}
}
