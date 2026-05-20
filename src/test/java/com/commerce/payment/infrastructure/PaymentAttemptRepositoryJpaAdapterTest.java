package com.commerce.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentAttemptRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@Import(PaymentAttemptRepositoryAdapter.class)
class PaymentAttemptRepositoryJpaAdapterTest {

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("merchantPayKey와 paymentId로 결제 승인 시도를 조회한다")
	@Test
	void findByMerchantPayKeyAndPaymentId_whenExists_returnAttempt() {
		// given
		PaymentAttempt attempt =
			PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		paymentAttemptRepository.save(attempt);
		entityManager.flush();
		entityManager.clear();

		// when
		Optional<PaymentAttempt> result =
			paymentAttemptRepository.findApproveAttempt("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1");

		// then
		assertThat(result).isPresent();
		assertThat(result.orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		assertThat(result.orElseThrow().getAmount()).isEqualTo(1000);
	}

	@DisplayName("승인 실패로 변경하면 상태와 실패 정보가 저장된다")
	@Test
	void save_whenApproveFailed_storeStatusAndFailInfo() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 0);
		PaymentAttempt attempt =
			PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		attempt.fail(PaymentAttemptFailCode.PG_NETWORK_ERROR, "network error", respondedAt);

		// when
		PaymentAttempt saved = paymentAttemptRepository.save(attempt);

		// then
		assertThat(saved.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(saved.getFailCode()).isEqualTo(PaymentAttemptFailCode.PG_NETWORK_ERROR);
		assertThat(saved.getFailDetail()).isEqualTo("network error");
		assertThat(saved.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("취소 요청 시도를 저장하면 취소 요청 상태로 저장된다")
	@Test
	void save_whenCancelRequested_storeCancelRequestedStatus() {
		// given
		PaymentAttempt attempt =
			PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);

		// when
		PaymentAttempt saved = paymentAttemptRepository.save(attempt);

		// then
		assertThat(saved.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		assertThat(saved.getAmount()).isEqualTo(1000);
	}

	@DisplayName("같은 결제번호라도 승인 시도와 취소 시도는 함께 저장할 수 있다")
	@Test
	void save_whenApproveAndCancelAttemptExist_storeBothAttempts() {
		// given
		PaymentAttempt approveAttempt =
			PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		PaymentAttempt cancelAttempt =
			PaymentAttempt.createCancelRequested("PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);

		// when
		paymentAttemptRepository.save(approveAttempt);
		paymentAttemptRepository.save(cancelAttempt);

		// then
		assertThat(paymentAttemptRepository.findApproveAttempt("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1"))
			.isPresent();
		assertThat(paymentAttemptRepository.findCancelAttempt("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1"))
			.isPresent();
	}
}
