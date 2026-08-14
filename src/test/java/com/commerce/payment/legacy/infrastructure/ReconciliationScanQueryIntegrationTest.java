package com.commerce.payment.legacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.commerce.common.jpa.JpaConfig;
import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.infrastructure.persistence.LegacyPaymentRepositoryAdapter;
import com.commerce.support.TestcontainersSupport;

@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, LegacyPaymentRepositoryAdapter.class})
class ReconciliationScanQueryIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private EntityManager em;

	// tbl_legacy_payment.order_id 는 cross-aggregate 참조를 ID로만 하는 설계라 FK 없는 단순 컬럼이므로, 실제 주문 없이 가상 ID를 사용한다.
	private static long nextOrderId = 9000L;

	private PaymentReservation reservation(String merchantPayKey) {
		return PaymentReservation.createReserved(
			++nextOrderId, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey,
			LocalDateTime.now().plusMinutes(15));
	}

	@DisplayName("APPROVE UNKNOWN 결제 중 respondedAt이 staleCutoff보다 과거이고 escalationCutoff보다 최근인 건이 대사 후보로 반환된다")
	@Test
	void findStaleApprovePayments_unknownBeforeCutoff_returned() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment payment = Payment.createRequested(reservation("PAY-SQ-1"), PaymentType.APPROVE, "pg-sq-1");
		payment.markUnknown("timeout", now.minusMinutes(2));
		paymentRepository.save(payment);

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-SQ-1");
		assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("APPROVE REQUESTED 결제 중 createdAt이 requestedStaleCutoff(15분)보다 과거이고 escalationCutoff보다 최근인 건이 대사 후보로 반환된다")
	@Test
	void findStaleApprovePayments_requestedBeforeCutoff_returned() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment payment = Payment.createRequested(reservation("PAY-SQ-2"), PaymentType.APPROVE, "pg-sq-2");
		Payment saved = paymentRepository.save(payment);

		// @CreatedDate/@LastModifiedDate Auditing을 우회하여 created_at을 REQUESTED 하한(15분)보다 과거인 16분 전으로 설정한다
		em.createNativeQuery("UPDATE tbl_legacy_payment SET created_at = :createdAt WHERE id = :id")
			.setParameter("createdAt", now.minusMinutes(16))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-SQ-2");
		assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
	}

	@DisplayName("createdAt이 requestedStaleCutoff(15분)보다 최근인 REQUESTED는 진입 지연 전이라 대사 후보에서 제외된다")
	@Test
	void findStaleApprovePayments_recentRequested_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment payment = Payment.createRequested(reservation("PAY-SQ-RR"), PaymentType.APPROVE, "pg-sq-rr");
		Payment saved = paymentRepository.save(payment);

		// createdAt을 2분 전으로 설정 → UNKNOWN 하한(1분)은 넘지만 REQUESTED 하한(15분)에는 못 미쳐 스캔 제외
		em.createNativeQuery("UPDATE tbl_legacy_payment SET created_at = :createdAt WHERE id = :id")
			.setParameter("createdAt", now.minusMinutes(2))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("SUCCEEDED, FAILED 상태 결제는 대사 후보에서 제외된다")
	@Test
	void findStaleApprovePayments_terminalStatuses_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime past = now.minusMinutes(3);
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment succeeded = Payment.createRequested(reservation("PAY-SQ-3-S"), PaymentType.APPROVE, "pg-sq-3-s");
		succeeded.succeed(past);
		paymentRepository.save(succeeded);

		Payment failed = Payment.createRequested(reservation("PAY-SQ-3-F"), PaymentType.APPROVE, "pg-sq-3-f");
		failed.fail(PaymentFailCode.PG_REQUEST_REJECTED, "rejected", past);
		paymentRepository.save(failed);

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("CANCEL 타입 결제는 type 필터에 의해 대사 후보에서 제외된다")
	@Test
	void findStaleApprovePayments_cancelType_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-SQ-4", "pg-sq-4", 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(cancelPayment);

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("staleCutoff 이후에 응답된 UNKNOWN 결제는 대사 후보에서 제외된다")
	@Test
	void findStaleApprovePayments_recentUnknown_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment recentUnknown = Payment.createRequested(reservation("PAY-SQ-5"), PaymentType.APPROVE, "pg-sq-5");
		recentUnknown.markUnknown("timeout", now); // respondedAt = now > staleCutoff
		paymentRepository.save(recentUnknown);

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("escalationCutoff보다 오래된 UNKNOWN 결제는 스캔 윈도우 상한 초과로 대사 후보에서 제외된다")
	@Test
	void findStaleApprovePayments_escalatedUnknown_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment escalatedUnknown = Payment.createRequested(reservation("PAY-SQ-ESC-1"), PaymentType.APPROVE, "pg-sq-esc-1");
		// respondedAt이 6시간 초과 → escalationCutoff보다 과거 → 스캔 제외
		escalatedUnknown.markUnknown("timeout", now.minusHours(7));
		paymentRepository.save(escalatedUnknown);

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("escalationCutoff보다 오래된 stale REQUESTED 결제는 스캔 윈도우 상한 초과로 대사 후보에서 제외된다")
	@Test
	void findStaleApprovePayments_escalatedRequested_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment escalatedRequested = Payment.createRequested(reservation("PAY-SQ-ESC-2"), PaymentType.APPROVE, "pg-sq-esc-2");
		Payment saved = paymentRepository.save(escalatedRequested);

		// createdAt을 7시간 전으로 설정 → escalationCutoff(6시간 전)보다 과거 → 스캔 제외
		em.createNativeQuery("UPDATE tbl_legacy_payment SET created_at = :createdAt WHERE id = :id")
			.setParameter("createdAt", now.minusHours(7))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("Pageable limit이 적용되어 지정한 수만큼만 반환된다")
	@Test
	void findStaleApprovePayments_pageableLimit_limitedResults() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);
		LocalDateTime past = now.minusMinutes(2);

		for (int i = 1; i <= 3; i++) {
			Payment p = Payment.createRequested(reservation("PAY-SQ-6-" + i), PaymentType.APPROVE, "pg-sq-6-" + i);
			p.markUnknown("timeout", past);
			paymentRepository.save(p);
		}

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 2));

		assertThat(result).hasSize(2);
	}

	// --- CANCEL 대사 스캔 쿼리 ---

	@DisplayName("CANCEL UNKNOWN 결제 중 respondedAt이 staleCutoff보다 과거이고 escalationCutoff보다 최근인 건이 CANCEL 대사 후보로 반환된다")
	@Test
	void findStaleCancelPayments_unknownBeforeCutoff_returned() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-CSQ-1", "pg-csq-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusMinutes(2));
		paymentRepository.save(cancelPayment);

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-CSQ-1");
		assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("CANCEL REQUESTED 결제 중 createdAt이 requestedStaleCutoff보다 과거이고 escalationCutoff보다 최근인 건이 CANCEL 대사 후보로 반환된다")
	@Test
	void findStaleCancelPayments_requestedBeforeCutoff_returned() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-CSQ-2", "pg-csq-2", 1000, PaymentProvider.NAVERPAY);
		Payment saved = paymentRepository.save(cancelPayment);

		em.createNativeQuery("UPDATE tbl_legacy_payment SET created_at = :createdAt WHERE id = :id")
			.setParameter("createdAt", now.minusMinutes(16))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-CSQ-2");
		assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
	}

	@DisplayName("APPROVE 타입 결제는 CANCEL 대사 스캔에서 제외된다")
	@Test
	void findStaleCancelPayments_approveType_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment approvePayment = Payment.createRequested(reservation("PAY-CSQ-3"), PaymentType.APPROVE, "pg-csq-3");
		approvePayment.markUnknown("timeout", now.minusMinutes(2));
		paymentRepository.save(approvePayment);

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("escalationCutoff보다 오래된 CANCEL UNKNOWN 결제는 스캔 윈도우 상한 초과로 CANCEL 대사 후보에서 제외된다")
	@Test
	void findStaleCancelPayments_escalatedUnknown_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-CSQ-ESC", "pg-csq-esc", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusHours(7));
		paymentRepository.save(cancelPayment);

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	// --- backoff 게이트 테스트 ---

	@DisplayName("APPROVE UNKNOWN 결제의 nextReconcileAt이 미래이면 backoff 게이트로 스캔에서 제외된다")
	@Test
	void findStaleApprovePayments_futureNextReconcileAt_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment payment = Payment.createRequested(reservation("PAY-SQ-BK-1"), PaymentType.APPROVE, "pg-sq-bk-1");
		payment.markUnknown("timeout", now.minusMinutes(2));
		Payment saved = paymentRepository.save(payment);

		// next_reconcile_at을 미래(5분 후)로 직접 세팅
		em.createNativeQuery("UPDATE tbl_legacy_payment SET next_reconcile_at = :nextReconcileAt WHERE id = :id")
			.setParameter("nextReconcileAt", now.plusMinutes(5))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("APPROVE UNKNOWN 결제의 nextReconcileAt이 NULL이면 즉시 대사 대상에 포함된다")
	@Test
	void findStaleApprovePayments_nullNextReconcileAt_included() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment payment = Payment.createRequested(reservation("PAY-SQ-BK-2"), PaymentType.APPROVE, "pg-sq-bk-2");
		payment.markUnknown("timeout", now.minusMinutes(2));
		paymentRepository.save(payment);
		// next_reconcile_at은 생성 시 NULL이므로 별도 세팅 불필요

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-SQ-BK-2");
	}

	@DisplayName("APPROVE UNKNOWN 결제의 nextReconcileAt이 과거이면 대사 대상에 포함된다")
	@Test
	void findStaleApprovePayments_pastNextReconcileAt_included() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment payment = Payment.createRequested(reservation("PAY-SQ-BK-3"), PaymentType.APPROVE, "pg-sq-bk-3");
		payment.markUnknown("timeout", now.minusMinutes(2));
		Payment saved = paymentRepository.save(payment);

		// next_reconcile_at을 과거(2분 전)로 세팅 → backoff 만료, 대사 대상
		em.createNativeQuery("UPDATE tbl_legacy_payment SET next_reconcile_at = :nextReconcileAt WHERE id = :id")
			.setParameter("nextReconcileAt", now.minusMinutes(2))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-SQ-BK-3");
	}

	@DisplayName("CANCEL UNKNOWN 결제의 nextReconcileAt이 미래이면 backoff 게이트로 CANCEL 스캔에서 제외된다")
	@Test
	void findStaleCancelPayments_futureNextReconcileAt_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-CSQ-BK-1", "pg-csq-bk-1", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusMinutes(2));
		Payment saved = paymentRepository.save(cancelPayment);

		// next_reconcile_at을 미래(5분 후)로 세팅
		em.createNativeQuery("UPDATE tbl_legacy_payment SET next_reconcile_at = :nextReconcileAt WHERE id = :id")
			.setParameter("nextReconcileAt", now.plusMinutes(5))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("CANCEL UNKNOWN 결제의 nextReconcileAt이 NULL이면 즉시 CANCEL 대사 대상에 포함된다")
	@Test
	void findStaleCancelPayments_nullNextReconcileAt_included() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-CSQ-BK-2", "pg-csq-bk-2", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusMinutes(2));
		paymentRepository.save(cancelPayment);

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-CSQ-BK-2");
	}

	@DisplayName("CANCEL UNKNOWN 결제의 nextReconcileAt이 과거이면 CANCEL 대사 대상에 포함된다")
	@Test
	void findStaleCancelPayments_pastNextReconcileAt_included() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(1);
		LocalDateTime escalationCutoff = now.minusHours(6);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(15);

		Payment cancelPayment = Payment.createCancelRequested(
			++nextOrderId, "PAY-CSQ-BK-3", "pg-csq-bk-3", 1000, PaymentProvider.NAVERPAY);
		cancelPayment.markUnknown("timeout", now.minusMinutes(2));
		Payment saved = paymentRepository.save(cancelPayment);

		// next_reconcile_at을 과거(2분 전)로 세팅 → backoff 만료, 대사 대상
		em.createNativeQuery("UPDATE tbl_legacy_payment SET next_reconcile_at = :nextReconcileAt WHERE id = :id")
			.setParameter("nextReconcileAt", now.minusMinutes(2))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("PAY-CSQ-BK-3");
	}
}
