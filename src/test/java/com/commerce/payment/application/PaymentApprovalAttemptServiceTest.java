package com.commerce.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentAttemptRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalAttemptServiceTest {

	@Mock
	private PaymentAttemptRepository paymentAttemptRepository;

	@InjectMocks
	private PaymentApprovalAttemptService paymentApprovalAttemptService;

	@DisplayName("같은 결제 시도 이력이 없으면 승인 요청 이력을 생성한다")
	@Test
	void getOrCreate_whenAttemptNotExists_createAttempt() {
		// given
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willAnswer(invocation -> invocation.getArgument(0, PaymentAttempt.class));

		// when
		PaymentAttempt result = paymentApprovalAttemptService.getOrCreate(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		assertThat(result.getType()).isEqualTo(PaymentAttemptType.APPROVE);
		assertThat(result.getAmount()).isEqualTo(1000);
	}

	@DisplayName("승인 시도 이력이 이미 존재하고 amount 가 같으면 기존 이력을 반환한다")
	@Test
	void getOrCreate_whenAttemptExistsWithSameAmount_returnExistingAttempt() {
		// given
		PaymentAttempt existingAttempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existingAttempt));

		// when
		PaymentAttempt result = paymentApprovalAttemptService.getOrCreate(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result).isSameAs(existingAttempt);
		then(paymentAttemptRepository).should(never()).save(any(PaymentAttempt.class));
	}

	@DisplayName("승인 시도 이력이 이미 존재하고 amount 가 다르면 예외를 던진다")
	@Test
	void getOrCreate_whenAttemptExistsWithDifferentAmount_throwAmountMismatch() {
		// given
		PaymentAttempt existing = PaymentAttempt.createApproveRequested(
			"PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existing));

		// when & then
		assertThatThrownBy(() -> paymentApprovalAttemptService.getOrCreate(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 2000))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
		then(paymentAttemptRepository).should(never()).save(any(PaymentAttempt.class));
	}

	@DisplayName("승인 성공 시 결제 시도 이력의 상태를 SUCCEEDED로 갱신한다")
	@Test
	void succeed_whenAttemptExists_updateAttempt() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(attempt));

		// when
		paymentApprovalAttemptService.succeed("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", respondedAt);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("승인 실패 시 결제 시도 이력의 실패 사유를 저장한다")
	@Test
	void fail_whenAttemptExists_updateAttempt() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(attempt));

		// when
		paymentApprovalAttemptService.fail("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentAttemptFailCode.PG_NETWORK_ERROR,
			"network error",
			respondedAt
		);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getFailCode()).isEqualTo(PaymentAttemptFailCode.PG_NETWORK_ERROR);
		assertThat(attempt.getFailDetail()).isEqualTo("network error");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("보상 흐름 실패 처리 시 REQUESTED 상태이면 실패로 전이한다")
	@Test
	void failIfRequested_whenRequested_updateAttempt() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(attempt));

		// when
		paymentApprovalAttemptService.failIfRequested("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
			"compensation",
			respondedAt
		);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getFailCode()).isEqualTo(PaymentAttemptFailCode.APPROVE_PROCESS_FAILED);
		assertThat(attempt.getFailDetail()).isEqualTo("compensation");
		assertThat(attempt.getRespondedAt()).isEqualTo(respondedAt);
	}

	@DisplayName("보상 흐름 실패 처리 시 REQUESTED 가 아니면 상태를 갱신하지 않고 종료한다")
	@Test
	void failIfRequested_whenNotRequested_skipMark() {
		// given
		LocalDateTime succeededAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		attempt.succeed(succeededAt);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(attempt));

		// when
		paymentApprovalAttemptService.failIfRequested("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
			"compensation",
			LocalDateTime.of(2026, 3, 3, 16, 22)
		);

		// then
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(attempt.getFailCode()).isNull();
		assertThat(attempt.getRespondedAt()).isEqualTo(succeededAt);
	}

	@DisplayName("보상 흐름 실패 처리 시 이력이 없으면 상태를 갱신하지 않고 종료한다")
	@Test
	void failIfRequested_whenAttemptNotFound_skipMark() {
		// given
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.empty());

		// when
		paymentApprovalAttemptService.failIfRequested(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
			PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
			"compensation",
			LocalDateTime.of(2026, 3, 3, 16, 21));

		// then
		then(paymentAttemptRepository).should(never()).save(any());
	}
}
