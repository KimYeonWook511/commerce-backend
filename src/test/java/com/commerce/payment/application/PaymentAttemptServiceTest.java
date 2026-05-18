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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentAttemptRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentAttemptServiceTest {

	@Mock
	private PaymentAttemptRepository paymentAttemptRepository;

	@InjectMocks
	private PaymentAttemptService paymentAttemptService;

	@DisplayName("같은 결제 시도 이력이 없으면 승인 요청 이력을 생성한다")
	@Test
	void getOrCreateApproveAttempt_whenAttemptNotExists_createAttempt() {
		// given
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willAnswer(invocation -> invocation.getArgument(0, PaymentAttempt.class));

		// when
		PaymentAttempt result = paymentAttemptService.getOrCreateApproveAttempt(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		assertThat(result.getAmount()).isEqualTo(1000);
	}

	@DisplayName("승인 시도 생성 중 유니크 충돌이 나면 재조회 결과를 반환한다")
	@Test
	void getOrCreateApproveAttempt_whenDuplicateOnSave_returnRefetchedAttempt() {
		// given
		PaymentAttempt existingAttempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existingAttempt));
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willThrow(new DuplicateKeyException("duplicate key"));

		// when
		PaymentAttempt result = paymentAttemptService.getOrCreateApproveAttempt(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result).isSameAs(existingAttempt);
	}

	@DisplayName("승인 실패 시 결제 시도 이력의 실패 사유를 저장한다")
	@Test
	void failApproveAttempt_whenAttemptExists_updateAttempt() {
		// given
		LocalDateTime respondedAt = LocalDateTime.of(2026, 3, 3, 16, 21);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "payment-id-1", 1000,
			PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(attempt));

		// when
		paymentAttemptService.failApproveAttempt("PAY-1", PaymentProvider.NAVERPAY, "payment-id-1",
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

	@DisplayName("취소 요청 이력이 없으면 취소 요청 이력을 생성한다")
	@Test
	void getOrCreateCancelAttempt_whenCancelAttemptNotExists_createCancelAttempt() {
		// given
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willAnswer(invocation -> invocation.getArgument(0, PaymentAttempt.class));

		// when
		PaymentAttempt result = paymentAttemptService.getOrCreateCancelAttempt(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000);

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		assertThat(result.getType()).isEqualTo(PaymentAttemptType.CANCEL);
		assertThat(result.getAmount()).isEqualTo(1000);
	}

	@DisplayName("취소 요청 생성 중 유니크 충돌이 나고 재조회도 실패하면 예외를 던진다")
	@Test
	void getOrCreateCancelAttempt_whenDuplicateOnSaveAndRefetchMissing_throwException() {
		// given
		given(paymentAttemptRepository.findCancelAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.<PaymentAttempt>empty());
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willThrow(new DuplicateKeyException("duplicate key"));

		// when & then
		assertThatThrownBy(() -> paymentAttemptService.getOrCreateCancelAttempt(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 1000))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
	}

	@DisplayName("승인 시도 생성 중 유니크 충돌이 나고 기존 amount와 다르면 예외를 던진다")
	@Test
	void getOrCreateApproveAttempt_whenDuplicateOnSaveWithDifferentAmount_throwAmountMismatch() {
		// given
		PaymentAttempt existing = PaymentAttempt.createApproveRequested(
			"PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findApproveAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existing));
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willThrow(new DuplicateKeyException("duplicate key"));

		// when & then
		assertThatThrownBy(() -> paymentAttemptService.getOrCreateApproveAttempt(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 2000))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
	}

	@DisplayName("취소 시도 생성 중 유니크 충돌이 나고 기존 amount와 다르면 예외를 던진다")
	@Test
	void getOrCreateCancelAttempt_whenDuplicateOnSaveWithDifferentAmount_throwAmountMismatch() {
		// given
		PaymentAttempt existing = PaymentAttempt.createCancelRequested(
			"PAY-1", "payment-id-1", 1000, PaymentProvider.NAVERPAY);
		given(paymentAttemptRepository.findCancelAttempt(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("payment-id-1")))
			.willReturn(Optional.of(existing));
		given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
			.willThrow(new DuplicateKeyException("duplicate key"));

		// when & then
		assertThatThrownBy(() -> paymentAttemptService.getOrCreateCancelAttempt(
			"PAY-1", PaymentProvider.NAVERPAY, "payment-id-1", 2000))
			.isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
	}

}
