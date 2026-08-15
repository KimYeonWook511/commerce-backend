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
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.exception.DuplicateRefundRequestException;
import com.commerce.payment.domain.repository.RefundRepository;
import com.commerce.support.TestcontainersSupport;

/**
 * 환불 요청 멱등키의 유일 범위와 배치 조회를 실제 DB로 확인한다. 찾는 범위와 제약 범위가 어긋나면
 * 조회가 못 찾은 것을 제약이 잡아 안전망이 정상 흐름에서 터지는데, 그 어긋남은 실제 제약 위에서만
 * 드러난다.
 */
@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, RefundRepositoryAdapter.class})
class RefundRepositoryAdapterIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private RefundRepository refundRepository;

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	// 결제는 식별자로만 참조하고 외래 키가 없으므로 실제 행 없이 가상 식별자를 쓴다.
	private static long nextPaymentId = 9000L;
	private static int nextRefundKeySeq = 0;

	private String newRefundKey() {
		return "RF-%032d".formatted(++nextRefundKeySeq);
	}

	private Refund openedRefund(long paymentId, RefundRequester requester, String idempotencyKey) {
		return Refund.open(paymentId, newRefundKey(), requester, idempotencyKey, 10_000,
			requester == RefundRequester.MEMBER ? RefundReason.ORDER_CANCELED : RefundReason.ORDER_NOT_PAYABLE);
	}

	@DisplayName("다른 결제에 같은 환불 요청 멱등키가 오면 각각 만들어진다")
	@Test
	void save_whenSameIdempotencyKeyOnDifferentPayments_createsBoth() {
		long firstPaymentId = ++nextPaymentId;
		long secondPaymentId = ++nextPaymentId;

		Refund first = refundRepository.save(openedRefund(firstPaymentId, RefundRequester.MEMBER, "IDEM-shared"));
		Refund second = refundRepository.save(openedRefund(secondPaymentId, RefundRequester.MEMBER, "IDEM-shared"));

		assertThat(first.getId()).isNotEqualTo(second.getId());
		assertThat(refundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
			firstPaymentId, RefundRequester.MEMBER, "IDEM-shared")).contains(first);
		assertThat(refundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
			secondPaymentId, RefundRequester.MEMBER, "IDEM-shared")).contains(second);
	}

	@DisplayName("같은 결제에 같은 요청자가 같은 환불 요청 멱등키를 다시 보내면 사건이 하나만 서고 도메인 예외로 옮겨진다")
	@Test
	void save_whenSameIdempotencyKeyOnSamePaymentAndRequester_translatesToDomainException() {
		long paymentId = ++nextPaymentId;
		refundRepository.save(openedRefund(paymentId, RefundRequester.MEMBER, "IDEM-dup"));

		// 같은 요청이 두 번 들어온 것만 도메인 언어로 옮긴다. 회원에게 "잠시 후 다시"라고 답할 수 있는
		// 유일한 위반이라 여기서 갈라야 위쪽이 그 뜻을 알 수 있다.
		assertThatThrownBy(() -> refundRepository.save(openedRefund(paymentId, RefundRequester.MEMBER, "IDEM-dup")))
			.isInstanceOf(DuplicateRefundRequestException.class);
	}

	@DisplayName("회원이 보낸 값과 시스템이 만든 값이 같은 문자열이어도 서로의 자리를 다투지 않는다")
	@Test
	void save_whenMemberAndSystemUseTheSameKeyString_createsBoth() {
		long paymentId = ++nextPaymentId;
		String sharedKey = RefundReason.ORDER_NOT_PAYABLE.name();

		Refund memberRefund = refundRepository.save(openedRefund(paymentId, RefundRequester.MEMBER, sharedKey));
		Refund systemRefund = refundRepository.save(openedRefund(paymentId, RefundRequester.SYSTEM, sharedKey));

		assertThat(memberRefund.getId()).isNotEqualTo(systemRefund.getId());
		assertThat(refundRepository.findSystemRefundByPaymentId(paymentId)).contains(systemRefund);
	}

	@DisplayName("환불 사건 키는 전체에서 유일해 두 사건이 나눠 가질 수 없다")
	@Test
	void save_whenTwoRefundsShareTheRefundKey_violatesUniqueConstraint() {
		String duplicatedRefundKey = newRefundKey();
		refundRepository.save(Refund.open(++nextPaymentId, duplicatedRefundKey, RefundRequester.MEMBER,
			"IDEM-key-1", 10_000, RefundReason.ORDER_CANCELED));

		assertThatThrownBy(() -> refundRepository.save(Refund.open(++nextPaymentId, duplicatedRefundKey,
			RefundRequester.MEMBER, "IDEM-key-2", 10_000, RefundReason.ORDER_CANCELED)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("두 환불이 같은 결제사 호출 멱등키를 나눠 가질 수 없다")
	@Test
	void save_whenTwoRefundsSharePgIdempotencyKey_violatesUniqueConstraint() {
		Refund first = refundRepository.save(openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-pg-1"));

		// 이 값은 사건 키에서 파생되므로 실제로 겹치는 것은 새 시도인데 시도 번호를 안 올린 코드 버그일
		// 때뿐이다. 그 버그가 남으면 결제사가 다른 환불의 결과를 이 환불의 결과로 돌려준다.
		Refund colliding = openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-pg-2");
		ReflectionTestUtils.setField(colliding, "pgIdempotencyKey", first.getPgIdempotencyKey());

		assertThatThrownBy(() -> refundRepository.save(colliding))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("아직 안 나간 환불은 시간 조건 없이 발송 대상이 된다")
	@Test
	void findDispatchTargets_includesRefundsThatHaveNotBeenSent() {
		Refund waiting = refundRepository.save(openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-send-1"));

		Refund sent = openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-send-2");
		sent.markInProgress(NOW);
		refundRepository.save(sent);

		List<Refund> targets = refundRepository.findDispatchTargets(PageRequest.of(0, 10));

		assertThat(targets).contains(waiting).doesNotContain(sent);
	}

	@DisplayName("대사 대상은 회차와 마지막으로 집은 시각으로 좁혀진다")
	@Test
	void findUnknownReconcileTargets_narrowsByReconcileCountAndPickedAt() {
		Refund firstRound = openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-rec-1");
		firstRound.markInProgress(NOW);
		firstRound.markUnknown();
		refundRepository.save(firstRound);

		Refund secondRound = openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-rec-2");
		secondRound.markInProgress(NOW);
		secondRound.markUnknown();
		secondRound.recordReconciled(NOW);
		refundRepository.save(secondRound);

		List<Refund> zeroRound = refundRepository.findUnknownReconcileTargets(
			0, 0, NOW.plusMinutes(1), PageRequest.of(0, 10));

		assertThat(zeroRound).contains(firstRound).doesNotContain(secondRound);
	}

	@DisplayName("사람이 처리해야 하는 환불은 승급을 기다리지 않고 통지 대상이 된다")
	@Test
	void findNotifyTargets_includesManualReviewWithoutEscalationDelay() {
		Refund manualReview = openedRefund(++nextPaymentId, RefundRequester.MEMBER, "IDEM-notify");
		manualReview.markInProgress(NOW);
		manualReview.flagForReview(RefundReviewCode.CANCEL_NOT_ALLOWED, "취소 불가");
		refundRepository.save(manualReview);

		List<Refund> targets = refundRepository.findNotifyTargets(
			LocalDateTime.now().minusDays(1), LocalDateTime.now(), PageRequest.of(0, 10));

		assertThat(targets).contains(manualReview);
	}
}
