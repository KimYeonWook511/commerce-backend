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
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.infrastructure.persistence.PaymentRepositoryAdapter;
import com.commerce.support.TestcontainersSupport;

@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, PaymentRepositoryAdapter.class})
class EscalationScanQueryIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private EntityManager em;

	private static long nextOrderId = 8000L;

	private PaymentReservation reservation(String merchantPayKey) {
		return PaymentReservation.createReserved(
			++nextOrderId, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey,
			LocalDateTime.now().plusMinutes(15));
	}

	@DisplayName("6시간 초과 UNKNOWN APPROVE 결제가 escalation 후보로 반환된다")
	@Test
	void findEscalationCandidates_unknownOverSixHours_returned() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment payment = Payment.createRequested(reservation("ESC-SQ-U-1"), PaymentType.APPROVE, "pg-esc-u-1");
		payment.markUnknown("timeout", now.minusHours(7));
		paymentRepository.save(payment);

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("ESC-SQ-U-1");
		assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("6시간 초과 REQUESTED APPROVE 결제가 escalation 후보로 반환된다")
	@Test
	void findEscalationCandidates_requestedOverSixHours_returned() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment payment = Payment.createRequested(reservation("ESC-SQ-R-1"), PaymentType.APPROVE, "pg-esc-r-1");
		Payment saved = paymentRepository.save(payment);

		em.createNativeQuery("UPDATE tbl_legacy_payment SET created_at = :createdAt WHERE id = :id")
			.setParameter("createdAt", now.minusHours(7))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMerchantPayKey()).isEqualTo("ESC-SQ-R-1");
		assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
	}

	@DisplayName("6시간 미만 UNKNOWN은 escalation 후보에서 제외된다")
	@Test
	void findEscalationCandidates_unknownWithinSixHours_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment payment = Payment.createRequested(reservation("ESC-SQ-U-2"), PaymentType.APPROVE, "pg-esc-u-2");
		payment.markUnknown("timeout", now.minusHours(5));
		paymentRepository.save(payment);

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("6시간 미만 REQUESTED는 escalation 후보에서 제외된다")
	@Test
	void findEscalationCandidates_requestedWithinSixHours_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment payment = Payment.createRequested(reservation("ESC-SQ-R-2"), PaymentType.APPROVE, "pg-esc-r-2");
		Payment saved = paymentRepository.save(payment);

		em.createNativeQuery("UPDATE tbl_legacy_payment SET created_at = :createdAt WHERE id = :id")
			.setParameter("createdAt", now.minusHours(5))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("escalatedAt이 이미 기록된 건은 escalatedAt IS NULL 필터로 후보에서 제외된다")
	@Test
	void findEscalationCandidates_alreadyEscalated_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment payment = Payment.createRequested(reservation("ESC-SQ-DONE-1"), PaymentType.APPROVE, "pg-esc-done-1");
		payment.markUnknown("timeout", now.minusHours(7));
		Payment saved = paymentRepository.save(payment);

		// escalated_at을 직접 set하여 이미 처리된 상태를 시뮬레이션
		em.createNativeQuery("UPDATE tbl_legacy_payment SET escalated_at = :escalatedAt WHERE id = :id")
			.setParameter("escalatedAt", now.minusMinutes(1))
			.setParameter("id", saved.getId())
			.executeUpdate();
		em.clear();

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("SUCCEEDED, FAILED 상태 결제는 escalation 후보에서 제외된다")
	@Test
	void findEscalationCandidates_terminalStatuses_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment succeeded = Payment.createRequested(reservation("ESC-SQ-S-1"), PaymentType.APPROVE, "pg-esc-s-1");
		succeeded.succeed(now.minusHours(7));
		paymentRepository.save(succeeded);

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("CANCEL 타입 결제는 escalation 후보에서 제외된다")
	@Test
	void findEscalationCandidates_cancelType_excluded() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment cancel = Payment.createCancelRequested(
			++nextOrderId, "ESC-SQ-C-1", "pg-esc-c-1", 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(cancel);

		List<Payment> result = paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10));

		assertThat(result).isEmpty();
	}

	@DisplayName("Payment.escalate() 호출 후 save하면 escalatedAt이 기록된다")
	@Test
	void escalate_unknownPayment_setsEscalatedAt() {
		LocalDateTime now = LocalDateTime.now();

		Payment payment = Payment.createRequested(reservation("ESC-SQ-UP-1"), PaymentType.APPROVE, "pg-esc-up-1");
		payment.markUnknown("timeout", now.minusHours(7));
		Payment saved = paymentRepository.save(payment);

		boolean escalated = saved.escalate(now);
		assertThat(escalated).isTrue();
		paymentRepository.save(saved);

		em.flush();
		em.clear();
		Payment reloaded = em.find(Payment.class, saved.getId());
		assertThat(reloaded.getEscalatedAt()).isNotNull();
	}

	@DisplayName("이미 escalatedAt이 기록된 Payment에 escalate()를 다시 호출하면 false를 반환한다(멱등)")
	@Test
	void escalate_alreadyEscalated_returnsFalse() {
		LocalDateTime now = LocalDateTime.now();

		Payment payment = Payment.createRequested(reservation("ESC-SQ-UP-2"), PaymentType.APPROVE, "pg-esc-up-2");
		payment.markUnknown("timeout", now.minusHours(7));
		Payment saved = paymentRepository.save(payment);

		// 첫 번째 escalation
		boolean first = saved.escalate(now);
		assertThat(first).isTrue();
		paymentRepository.save(saved);

		// reload 후 두 번째 시도 — escalatedAt이 있으므로 false
		em.flush();
		em.clear();
		Payment reloaded = em.find(Payment.class, saved.getId());
		boolean second = reloaded.escalate(now);
		assertThat(second).isFalse();
	}

	@DisplayName("escalate() + save 후 해당 건이 escalatedAt IS NULL 필터로 다음 스캔에서 제외된다")
	@Test
	void escalate_thenExcludedFromNextScan() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime escalationCutoff = now.minusHours(6);

		Payment payment = Payment.createRequested(reservation("ESC-SQ-EX-1"), PaymentType.APPROVE, "pg-esc-ex-1");
		payment.markUnknown("timeout", now.minusHours(7));
		Payment saved = paymentRepository.save(payment);

		// escalation 전: 스캔에 포함됨
		assertThat(paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10)))
			.hasSize(1);

		// escalation 처리
		saved.escalate(now);
		paymentRepository.save(saved);
		em.flush();
		em.clear();

		// escalation 후: escalatedAt IS NULL 아님이므로 스캔에서 제외됨
		assertThat(paymentRepository.findEscalationCandidates(escalationCutoff, PageRequest.of(0, 10)))
			.isEmpty();
	}
}
