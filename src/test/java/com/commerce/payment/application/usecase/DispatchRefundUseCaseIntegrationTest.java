package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 발송 배치가 아직 안 나간 환불을 집어 보내는지 확인한다. 이 자리가 없으면 요청 흐름이 커밋하고 죽은
 * 환불이 영영 안 나가므로, 조회 조건과 그 결과가 실제 DB 위에서 확인되어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class
})
class DispatchRefundUseCaseIntegrationTest {

	private static final int AMOUNT = 10_000;

	@Autowired
	private DispatchRefundUseCase dispatchRefundUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private RefundPersistenceTestSupport refundPersistence;

	@Autowired
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(pgCallLogPersistence, refundPersistence, paymentPersistence);
	}

	@DisplayName("결제사를 부르기 전에 끊겨 접수 상태로 남은 환불을 배치가 집어 보낸다")
	@Test
	void dispatch_whenRefundNeverSent_sendsIt() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.succeeded("pg-cancel-tx-1", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}")));

		dispatchRefundUseCase.dispatch();

		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		// 한 번도 안 부른 건은 이력을 읽지 않고 바로 나간다.
		then(paymentGatewayPort).should(never()).readHistory(any(), any());
		then(paymentGatewayPort).should().refund(any(), any(), eq(PgCallSource.BATCH));
	}

	@DisplayName("이미 보낸 환불은 배치가 집지 않는다")
	@Test
	void dispatch_whenRefundAlreadyInProgress_skipsIt() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		refund.markInProgress(LocalDateTime.now());
		refundPersistence.save(refund);

		dispatchRefundUseCase.dispatch();

		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
	}

	@DisplayName("한 건이 실패해도 나머지 환불은 그대로 나간다")
	@Test
	void dispatch_whenOneRefundFails_keepsSendingTheRest() {
		Payment first = savePayment();
		Refund failing = saveRefund(first);
		Payment second = savePayment();
		Refund succeeding = saveRefund(second);

		given(paymentGatewayPort.refund(any(), any(), any())).willAnswer(invocation -> {
			Refund target = invocation.getArgument(1);
			if (target.getId().equals(failing.getId())) {
				throw new IllegalStateException("보내다 깨졌다");
			}
			return PgRefundResult.succeeded("pg-cancel-tx-2", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}"));
		});

		dispatchRefundUseCase.dispatch();

		assertThat(reload(succeeding).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
	}

	// ── 헬퍼 ──

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			300L + suffix, 400L + suffix, PaymentPg.NAVERPAY, "PK-D-" + suffix, "idem-d-" + suffix, AMOUNT);
		payment.markInProgress("pg-payment-d-" + suffix, LocalDateTime.now());
		payment.succeed(AMOUNT, "pg-tx-d-" + suffix);
		return paymentPersistence.save(payment);
	}

	private Refund saveRefund(Payment payment) {
		Refund refund = payment.openRefund(
			Optional.empty(), AMOUNT, RefundReason.ORDER_CANCELED, "IDEM-D-" + uniqueSuffix);
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	private Refund reload(Refund refund) {
		return refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}
}
