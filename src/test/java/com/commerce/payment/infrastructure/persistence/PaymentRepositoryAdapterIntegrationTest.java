package com.commerce.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.support.TestcontainersSupport;

/**
 * 새 결제 테이블의 유일 제약과 배치 조회를 실제 DB로 확인한다. 활성 슬롯과 대사 대상 조회는 인덱스와
 * 유일 제약 위에 서 있어 대역으로는 거동이 재현되지 않는다.
 */
@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, PaymentRepositoryAdapter.class})
class PaymentRepositoryAdapterIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PaymentRepository paymentRepository;

	private static final Long MEMBER_ID = 900L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	// 주문·회원은 식별자로만 참조하고 외래 키가 없으므로 실제 행 없이 가상 식별자를 쓴다.
	private static long nextOrderId = 9000L;

	private Payment startedPayment(long orderId, String suffix) {
		return Payment.start(orderId, MEMBER_ID, PaymentPg.NAVERPAY, "PK-" + suffix, "IDEM-" + suffix, 10_000);
	}

	@DisplayName("한 주문에 활성 슬롯을 쥔 결제가 둘일 수 없다")
	@Test
	void save_whenAnotherPaymentHoldsActiveSlot_violatesUniqueConstraint() {
		long orderId = ++nextOrderId;
		paymentRepository.save(startedPayment(orderId, "dup-1"));

		assertThatThrownBy(() -> paymentRepository.save(startedPayment(orderId, "dup-2")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("앞 결제의 슬롯을 먼저 비워 저장하면 같은 트랜잭션에서 새 결제가 그 자리를 잡는다")
	@Test
	void saveFlushed_whenReleasingSlotBeforeInsert_letsNextPaymentTakeIt() {
		long orderId = ++nextOrderId;
		Payment previous = paymentRepository.save(startedPayment(orderId, "slot-1"));

		previous.expire(PaymentCloseCode.SUPERSEDED);
		paymentRepository.saveFlushed(previous);
		Payment next = paymentRepository.save(startedPayment(orderId, "slot-2"));

		assertThat(previous.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThat(previous.getActiveOrderKey()).isNull();
		assertThat(next.getActiveOrderKey()).isEqualTo(orderId);
		assertThat(paymentRepository.findActiveByOrderId(orderId)).contains(next);
	}

	@DisplayName("같은 회원이 같은 결제 시작 멱등키를 두 번 보내면 결제가 하나만 선다")
	@Test
	void save_whenSameMemberSendsSameIdempotencyKey_violatesUniqueConstraint() {
		Payment first = Payment.start(++nextOrderId, MEMBER_ID, PaymentPg.NAVERPAY, "PK-idem-1", "IDEM-same", 10_000);
		Payment second = Payment.start(++nextOrderId, MEMBER_ID, PaymentPg.NAVERPAY, "PK-idem-2", "IDEM-same", 10_000);
		paymentRepository.save(first);

		assertThatThrownBy(() -> paymentRepository.save(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("결제 키로 찾을 때 회원이 다르면 없는 것으로 답한다")
	@Test
	void findByPaymentKeyAndMemberId_whenMemberDiffers_returnsEmpty() {
		long orderId = ++nextOrderId;
		paymentRepository.save(startedPayment(orderId, "owner"));

		assertThat(paymentRepository.findByPaymentKeyAndMemberId("PK-owner", MEMBER_ID)).isPresent();
		assertThat(paymentRepository.findByPaymentKeyAndMemberId("PK-owner", MEMBER_ID + 1)).isEmpty();
	}

	@DisplayName("회수 경로는 회원 없이 결제 키만으로 남의 결제를 찾는다")
	@Test
	void findByPaymentKey_whenReclaiming_findsPaymentOfAnotherMember() {
		long orderId = ++nextOrderId;
		paymentRepository.save(startedPayment(orderId, "reclaim"));

		assertThat(paymentRepository.findByPaymentKey("PK-reclaim")).isPresent();
	}

	@DisplayName("결과 불명 결제가 있는 주문을 알아본다")
	@Test
	void existsUnknownByOrderId_whenPaymentResultIsUnknown_returnsTrue() {
		long orderId = ++nextOrderId;
		Payment payment = startedPayment(orderId, "unknown");
		payment.markInProgress("pg-unknown", NOW);
		payment.markUnknown();
		paymentRepository.save(payment);

		assertThat(paymentRepository.existsUnknownByOrderId(orderId)).isTrue();
		assertThat(paymentRepository.existsUnknownByOrderId(orderId + 1)).isFalse();
	}

	@DisplayName("그 회원의 주문에서 성공한 결제를 찾는다")
	@Test
	void findSucceededByMemberIdAndOrderId_whenPaymentSucceeded_returnsIt() {
		long orderId = ++nextOrderId;
		Payment payment = startedPayment(orderId, "succeeded");
		payment.markInProgress("pg-succeeded", NOW);
		payment.succeed(10_000, "pg-tx-1");
		paymentRepository.save(payment);

		assertThat(paymentRepository.findSucceededByMemberIdAndOrderId(MEMBER_ID, orderId)).isPresent();
		assertThat(paymentRepository.findSucceededByMemberIdAndOrderId(MEMBER_ID + 1, orderId)).isEmpty();
	}

	@DisplayName("대사 대상은 상태와 회차와 마지막으로 집은 시각으로 좁혀진다")
	@Test
	void findUnknownReconcileTargets_narrowsByReconcileCountAndPickedAt() {
		Payment firstRound = startedPayment(++nextOrderId, "rec-1");
		firstRound.markInProgress("pg-rec-1", NOW);
		firstRound.markUnknown();
		paymentRepository.save(firstRound);

		Payment secondRound = startedPayment(++nextOrderId, "rec-2");
		secondRound.markInProgress("pg-rec-2", NOW);
		secondRound.markUnknown();
		secondRound.recordReconciled(NOW);
		paymentRepository.save(secondRound);

		List<Payment> zeroRound = paymentRepository.findUnknownReconcileTargets(
			0, 0, NOW.plusMinutes(1), PageRequest.of(0, 10));
		List<Payment> firstRoundTargets = paymentRepository.findUnknownReconcileTargets(
			1, 1, NOW.plusMinutes(1), PageRequest.of(0, 10));

		assertThat(zeroRound).contains(firstRound).doesNotContain(secondRound);
		assertThat(firstRoundTargets).contains(secondRound).doesNotContain(firstRound);
	}

	@DisplayName("마지막 회차 조회는 그 이상을 전부 받아 회수가 멈추지 않는다")
	@Test
	void findUnknownReconcileTargets_whenLastStage_includesHigherCounts() {
		Payment payment = startedPayment(++nextOrderId, "rec-last");
		payment.markInProgress("pg-rec-last", NOW);
		payment.markUnknown();
		payment.recordReconciled(NOW);
		payment.recordReconciled(NOW);
		payment.recordReconciled(NOW);
		paymentRepository.save(payment);

		List<Payment> targets = paymentRepository.findUnknownReconcileTargets(
			2, Integer.MAX_VALUE, NOW.plusMinutes(1), PageRequest.of(0, 10));

		assertThat(targets).contains(payment);
	}

	@DisplayName("승인을 부른 지 대사 유예가 지난 건만 대사 대상이 된다")
	@Test
	void findInProgressReconcileTargets_skipsRecentlyRequested() {
		Payment payment = startedPayment(++nextOrderId, "rec-grace");
		payment.markInProgress("pg-rec-grace", NOW);
		paymentRepository.save(payment);

		List<Payment> beforeGrace = paymentRepository.findInProgressReconcileTargets(
			NOW, 0, 0, NOW.plusMinutes(1), PageRequest.of(0, 10));
		List<Payment> afterGrace = paymentRepository.findInProgressReconcileTargets(
			NOW.plusMinutes(1), 0, 0, NOW.plusMinutes(1), PageRequest.of(0, 10));

		assertThat(beforeGrace).doesNotContain(payment);
		assertThat(afterGrace).contains(payment);
	}

	@DisplayName("통지 대상은 알린 지 반복 간격이 지난 건만 다시 고른다")
	@Test
	void findNotifyTargets_skipsRecentlyNotified() {
		Payment payment = startedPayment(++nextOrderId, "notify");
		payment.markInProgress("pg-notify", NOW);
		payment.markUnknown();
		payment.recordNotified(NOW.plusHours(1));
		paymentRepository.save(payment);

		List<Payment> tooSoon = paymentRepository.findNotifyTargets(
			LocalDateTime.now().plusDays(1), NOW.plusMinutes(30), PageRequest.of(0, 10));
		List<Payment> dueAgain = paymentRepository.findNotifyTargets(
			LocalDateTime.now().plusDays(1), NOW.plusHours(2), PageRequest.of(0, 10));

		assertThat(tooSoon).doesNotContain(payment);
		assertThat(dueAgain).contains(payment);
	}

	@DisplayName("만료 대상은 승인을 아직 부르지 않은 결제뿐이다")
	@Test
	void findExpireTargets_onlyIncludesPaymentsBeforeApprovalCall() {
		Payment ready = startedPayment(++nextOrderId, "exp-ready");
		paymentRepository.save(ready);

		Payment called = startedPayment(++nextOrderId, "exp-called");
		called.markInProgress("pg-exp", NOW);
		paymentRepository.save(called);

		List<Payment> targets = paymentRepository.findExpireTargets(
			LocalDateTime.now().plusDays(1), PageRequest.of(0, 10));

		assertThat(targets).contains(ready).doesNotContain(called);
	}
}
