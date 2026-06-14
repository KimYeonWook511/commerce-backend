package com.commerce.payment.naverpay.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.service.PaymentApprovalRecordService;
import com.commerce.payment.application.usecase.PaymentApprovalCompensationUseCase;
import com.commerce.payment.application.service.PaymentApprovalService;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentReservationStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.naverpay.application.dto.NaverPayApproveResponse;
import com.commerce.payment.naverpay.application.dto.NaverPayApproveStatus;

@ExtendWith(MockitoExtension.class)
class NaverPayApprovalUseCaseTest {

	@Mock
	private NaverPayGateway naverPayGateway;

	@Mock
	private PaymentApprovalService paymentApprovalService;

	@Mock
	private PaymentApprovalRecordService paymentApprovalRecordService;

	@Mock
	private PaymentApprovalCompensationUseCase paymentApprovalCompensationUseCase;

	@Mock
	private PaymentReservationRepository paymentReservationRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private OrderRepository orderRepository;

	@InjectMocks
	private NaverPayApprovalUseCase naverPayApprovalUseCase;

	@DisplayName("예약 정보가 없으면 PAYMENT_RESERVATION_NOT_FOUND를 던진다")
	@Test
	void approve_whenReservationNotFound_throwPaymentReservationNotFound() {
		// given
		long memberId = 1L;
		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESERVATION_NOT_FOUND);
			});
	}

	@DisplayName("남의 merchantPayKey로 승인 요청하면 PAYMENT_RESERVATION_NOT_FOUND를 던진다 (키 존재 비노출)")
	@Test
	void approve_whenMemberDoesNotOwnKey_throwPaymentReservationNotFound() {
		// given: attacker(memberId=2)가 owner(memberId=1)의 merchantPayKey로 요청
		long attackerMemberId = 2L;
		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(attackerMemberId, "PAY-1"))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(attackerMemberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESERVATION_NOT_FOUND);
			});
	}

	@DisplayName("UNKNOWN 상태 Payment가 있는 주문은 approve 진입 시 PAYMENT_RESULT_PENDING을 던진다")
	@Test
	void approve_whenUnknownByOrderId_throwsPaymentResultPending() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(reservation.getOrderId())).willReturn(true);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			});
		then(naverPayGateway).should(never()).approve(any());
		then(paymentApprovalRecordService).should(never()).create(any(), any());
	}

	@DisplayName("USED Reservation에 같은 merchantPayKey redirect가 중복 도착하면 기존 결제 결과를 반환한다 (멱등 응답)")
	@Test
	void approve_whenReservationUsedAndApproveSucceededExists_returnsIdempotentResponse() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createUsedReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment succeededPayment = createPayment("PAY-1", "pg-payment-id", 1000);
		succeededPayment.succeed(LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(reservation.getOrderId())).willReturn(false);
		given(paymentRepository.findApprovePayment("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id"))
			.willReturn(Optional.of(succeededPayment));

		// when
		assertThatNoException().isThrownBy(() -> {
			NaverPayApproveResponse result = naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id");

			// then: 멱등 200 응답 — PG 호출 0회, 새 payment/reservation save 0회
			assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
			assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		});
		then(naverPayGateway).should(never()).approve(any());
		then(paymentApprovalRecordService).should(never()).create(any(), any());
		then(paymentRepository).should(never()).save(any());
		then(paymentReservationRepository).should(never()).save(any());
	}

	@DisplayName("USED Reservation이지만 기존 시도가 REQUESTED로 미완료면 PG를 재확인해 결제를 완료한다 (영구 차단 방지)")
	@Test
	void approve_whenReservationUsedAndPaymentRequested_reprocessesPgAndCompletes() {
		// given: 첫 redirect에서 payment 생성 후 PG PROCESSING/중단으로 REQUESTED 잔존, reservation은 USED
		long memberId = 1L;
		PaymentReservation reservation = createUsedReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment requestedPayment = createPayment("PAY-1", "pg-payment-id", 1000);
		Payment completedPayment = createPayment("PAY-1", "pg-payment-id", 1000);
		completedPayment.succeed(LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(reservation.getOrderId())).willReturn(false);
		given(paymentRepository.findApprovePayment("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id"))
			.willReturn(Optional.of(requestedPayment));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any())).willReturn(completedPayment);

		// when
		NaverPayApproveResponse result = naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id");

		// then: 새 payment 생성 없이 기존 REQUESTED 시도를 재확인해 성공 완료
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(naverPayGateway).should().approve("pg-payment-id");
		then(paymentApprovalRecordService).should(never()).create(any(), any());
	}

	@DisplayName("승인 응답 코드가 Success면 결제 완료를 반영하고 성공 결과를 반환한다")
	@Test
	void approve_whenApproveResponseIsSuccess_completePayment() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);
		Payment completedPayment = createPayment("PAY-1", "pg-payment-id", 1000);
		completedPayment.succeed(LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any())).willReturn(completedPayment);

		// when
		NaverPayApproveResponse result = naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(paymentApprovalRecordService).should()
			.create(any(PaymentReservation.class), eq("pg-payment-id"));
		then(paymentApprovalService).should().succeedApproval(any(Payment.class), any());
	}

	@DisplayName("이미 진행 중이면 처리 중 상태를 반환한다")
	@Test
	void approve_whenAlreadyOnGoing_returnProcessing() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.processing());

		// when
		NaverPayApproveResponse result = naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	@DisplayName("승인 응답 코드가 AlreadyComplete면 승인 이력을 조회해 결제 완료를 반영한다")
	@Test
	void approve_whenApproveResponseIsAlreadyComplete_completePayment() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);
		Payment completedPayment = createPayment("PAY-1", "pg-payment-id", 1000);
		completedPayment.succeed(LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any())).willReturn(completedPayment);

		// when
		NaverPayApproveResponse result = naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(paymentApprovalService).should().succeedApproval(any(Payment.class), any());
	}

	@DisplayName("AlreadyComplete 경로에서 history 조회가 결과 불명(UNKNOWN)이면 markUnknown를 호출하고 PAYMENT_RESULT_PENDING을 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryUnknown_callsMarkUnknownAndThrowsResultPending() {
		// given: AlreadyComplete는 PG가 이미 처리한 상태이므로 이력조회가 결과 불명이면
		// UNKNOWN 흔적을 남겨 재시도를 차단해야 한다 ("결제됐는데 미결제 박제" 방지, #219)
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.unknown("이력조회 결과 불명: 네트워크 오류"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			});
		then(paymentApprovalRecordService).should().markUnknown(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any(), any());
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	@DisplayName("AlreadyComplete 경로에서 history merchantPayKey가 다르면 approve payment를 실패 처리하고 PAYMENT_NOT_FOUND를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryMerchantPayKeyMismatch_markFailedAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		then(paymentApprovalRecordService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH), eq("가맹점 결제 키 불일치"), any());
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("이미 완료된 승인 응답에서 승인 이력 조회 코드가 실패면 매핑된 예외를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCodeIsInvalidMerchant_throwException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_INVALID_MERCHANT));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_INVALID_MERCHANT);
			});
	}

	@DisplayName("AlreadyComplete 경로에서 최신 이력이 승인도 취소도 아니면 PAYMENT_NOT_FOUND를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryNotCompleted_throwNotFound() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("AlreadyComplete 경로에서 history가 취소 완료 상태면 approve payment를 ALREADY_CANCELED로 실패 처리하고 PAYMENT_ALREADY_CANCELED를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCanceled_markAlreadyCanceledAndThrowAlreadyCanceled() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id")).willReturn(NaverPayHistoryResult.canceled());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
			});
		then(paymentApprovalRecordService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentFailCode.ALREADY_CANCELED), eq("이미 취소된 결제"), any());
	}

	@DisplayName("AlreadyComplete 경로에서 승인 이력이 비어있으면 결제를 찾을 수 없다고 처리한다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryListEmpty_throwNotFound() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("네트워크 오류면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenNetworkException_markPaymentFailedAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_NETWORK_ERROR, PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR, "network error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR);
			});
		then(paymentApprovalRecordService).should().fail(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), eq(PaymentFailCode.PG_NETWORK_ERROR), eq("network error"), any());
	}

	@DisplayName("서버 오류면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenServerException_markPaymentFailedAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_SERVER_ERROR, PaymentErrorCode.PAYMENT_PG_SERVER_ERROR, "server error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_SERVER_ERROR);
			});
		then(paymentApprovalRecordService).should().fail(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), eq(PaymentFailCode.PG_SERVER_ERROR), eq("server error"), any());
	}

	@DisplayName("응답 파싱 오류면 결제 시도만 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenInvalidResponse_markPaymentFailedAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_INVALID_RESPONSE, PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE, "invalid response"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE);
			});
		then(paymentApprovalRecordService).should().fail(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentFailCode.PG_INVALID_RESPONSE), eq("invalid response"), any());
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	@DisplayName("승인 가능 시간이 초과되면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenTimeExpired_markPaymentFailedAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.TIME_EXPIRED, PaymentErrorCode.PAYMENT_TIME_EXPIRED, "결제 승인 가능 시간 초과 시 (10분 초과시)"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED);
			});
		then(paymentApprovalRecordService).should().fail(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentFailCode.TIME_EXPIRED), eq("결제 승인 가능 시간 초과 시 (10분 초과시)"), any());
	}

	@DisplayName("PG 점검 코드면 결제 시도를 점검 사유로 실패 기록하고 예외를 던진다")
	@Test
	void approve_whenMaintenanceCode_markPaymentFailedAndThrowMaintenanceException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_MAINTENANCE, PaymentErrorCode.PAYMENT_PG_MAINTENANCE, "서비스 점검중"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_MAINTENANCE);
			});
		then(paymentApprovalRecordService).should().fail(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentFailCode.PG_MAINTENANCE), eq("서비스 점검중"), any());
	}

	@DisplayName("게이트웨이 UNKNOWN 결과 시 markUnknown를 호출하고 PAYMENT_RESULT_PENDING을 던진다")
	@Test
	void approve_whenGatewayReturnsUnknown_callsMarkUnknownAndThrowsResultPending() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.unknown("승인 호출 중 네트워크 오류: timeout"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			});
		then(paymentApprovalRecordService).should().markUnknown(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any(), any());
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	@DisplayName("승인 응답 merchantPayKey가 다르면 compensateMerchantKeyMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenApproveResponseMerchantPayKeyMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.success("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentApprovalCompensationUseCase).should().compensateMerchantKeyMismatch(any());
	}

	@DisplayName("승인 금액이 다르면 compensateAmountMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenAmountMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 2000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentApprovalCompensationUseCase).should().compensateAmountMismatch(any(), eq(2000), any());
	}

	@DisplayName("succeedApproval이 PAYMENT_DUPLICATE를 던지면 compensateDuplicatePayment를 호출하고 PAYMENT_DUPLICATE를 전파한다")
	@Test
	void approve_whenSucceedApprovalThrowsPaymentDuplicate_callsCompensateDuplicatePayment() {
		// given: adapter가 uk_payment_approved_order_key 위반을 PAYMENT_DUPLICATE로 매핑해 전달하는 시나리오
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
		then(paymentApprovalCompensationUseCase).should().compensateDuplicatePayment(any(), any(), any());
	}

	@DisplayName("결제 완료 반영 중 merchantPayKey 불일치 예외가 발생하면 compensateMerchantKeyMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsMerchantKeyMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentApprovalCompensationUseCase).should().compensateMerchantKeyMismatch(any());
	}

	@DisplayName("결제 완료 반영 중 금액 불일치 예외가 발생하면 compensateAmountMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsAmountMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentApprovalCompensationUseCase).should().compensateAmountMismatch(any(), eq(1000), any());
	}

	@DisplayName("결제 완료 반영 중 CustomException(주문 예외)이 발생하면 보상 없이 예외를 전파하고 approve는 REQUESTED로 남는다")
	@Test
	void approve_whenCompleteApproveThrowsOrderException_propagatesWithoutCompensation() {
		// given: PG SUCCESS + verify 통과 후 DB 기록 실패(CustomException 계열)
		// → 정상 매출을 취소하지 않고 예외 전파, approve REQUESTED 유지 → reconcile self-heal (ADR-L1)
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any()))
			.willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
		// 보상(PG cancel, fail)이 발생하지 않는다 — REQUESTED 유지로 reconcile self-heal
		then(paymentApprovalCompensationUseCase).should(never()).compensateMerchantKeyMismatch(any());
		then(paymentApprovalCompensationUseCase).should(never()).compensateAmountMismatch(any(), anyInt(), any());
		then(paymentApprovalCompensationUseCase).should(never()).compensateDuplicatePayment(any(), any(), any());
	}

	@DisplayName("결제 완료 반영 중 unmapped PaymentException이 발생하면 보상 없이 예외를 전파하고 approve는 REQUESTED로 남는다")
	@Test
	void approve_whenCompleteApproveThrowsUnhandledPaymentException_propagatesWithoutCompensation() {
		// given: PG SUCCESS + verify 통과 후 DB 기록 실패(unmapped PaymentException)
		// → 버그 또는 transient 실패를 환불·FAILED로 박제하지 않고 전파(500), approve REQUESTED 유지 (ADR-L1)
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
			});
		// 보상(PG cancel, fail)이 발생하지 않는다 — REQUESTED 유지로 reconcile self-heal
		then(paymentApprovalCompensationUseCase).should(never()).compensateMerchantKeyMismatch(any());
		then(paymentApprovalCompensationUseCase).should(never()).compensateAmountMismatch(any(), anyInt(), any());
		then(paymentApprovalCompensationUseCase).should(never()).compensateDuplicatePayment(any(), any(), any());
	}

	@DisplayName("결제 완료 반영 중 예상하지 못한 예외(RuntimeException)가 발생하면 보상 없이 예외를 전파하고 approve는 REQUESTED로 남는다")
	@Test
	void approve_whenCompleteApproveThrowsUnexpectedException_propagatesWithoutCompensation() {
		// given: PG SUCCESS + verify 통과 후 DB 기록 실패(일반 Exception — transient 또는 버그)
		// → UNKNOWN/FAILED 둔갑 없이 전파(500), approve REQUESTED 유지 → reconcile self-heal (ADR-L1)
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.succeedApproval(any(Payment.class), any()))
			.willThrow(new RuntimeException("db write failed"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("db write failed");
		// 보상(PG cancel, fail)이 발생하지 않는다 — REQUESTED 유지로 reconcile self-heal
		then(paymentApprovalCompensationUseCase).should(never()).compensateMerchantKeyMismatch(any());
		then(paymentApprovalCompensationUseCase).should(never()).compensateAmountMismatch(any(), anyInt(), any());
		then(paymentApprovalCompensationUseCase).should(never()).compensateDuplicatePayment(any(), any(), any());
	}

	@DisplayName("다른 사용자의 pgPaymentId로 승인 응답을 받으면 compensateMerchantKeyMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenForeignPgPaymentIdReturnsDifferentMerchantPayKey_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		PaymentReservation attackerReservation = createReservation("PAY-ATTACKER", memberId, 1000);
		Order attackerOrder = createOrder(1000);
		Payment attackerPayment = createPayment("PAY-ATTACKER", "pg-victim-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-ATTACKER")).willReturn(Optional.of(attackerReservation));
		given(orderRepository.findByIdAndMemberId(attackerReservation.getOrderId(), memberId)).willReturn(Optional.of(attackerOrder));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-victim-payment-id")))
			.willReturn(attackerPayment);
		given(naverPayGateway.approve("pg-victim-payment-id"))
			.willReturn(NaverPayApproveResult.success("PAY-VICTIM", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-ATTACKER", "pg-victim-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentApprovalCompensationUseCase).should().compensateMerchantKeyMismatch(any());
	}

	@DisplayName("다른 사용자의 pgPaymentId로 AlreadyComplete 응답을 받았고 history merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPgPaymentIdHistoryReturnsDifferentMerchantPayKey_markFailedWithoutCancel() {
		// given
		long memberId = 1L;
		PaymentReservation attackerReservation = createReservation("PAY-ATTACKER", memberId, 1000);
		Order attackerOrder = createOrder(1000);
		Payment attackerPayment = createPayment("PAY-ATTACKER", "pg-victim-payment-id", 1000);

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-ATTACKER")).willReturn(Optional.of(attackerReservation));
		given(orderRepository.findByIdAndMemberId(attackerReservation.getOrderId(), memberId)).willReturn(Optional.of(attackerOrder));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-victim-payment-id")))
			.willReturn(attackerPayment);
		given(naverPayGateway.approve("pg-victim-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-victim-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("PAY-VICTIM", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-ATTACKER", "pg-victim-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		then(paymentApprovalRecordService).should().fail(
			eq("PAY-ATTACKER"), eq(PaymentProvider.NAVERPAY), eq("pg-victim-payment-id"),
			eq(PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH), eq("가맹점 결제 키 불일치"), any());
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("같은 결제 시도 이력이 실패 상태면 예외를 던진다")
	@Test
	void approve_whenPaymentFailed_throwException() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);
		payment.fail(PaymentFailCode.TIME_EXPIRED, "expired", LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED);
			});
		then(naverPayGateway).should(never()).approve(any());
	}

	@DisplayName("payment가 UNKNOWN 상태면 PAYMENT_RESULT_PENDING를 던진다")
	@Test
	void approve_whenPaymentIsUnknown_throwResultPending() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);
		payment.markUnknown("PG 응답 불명확", LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			});
		then(naverPayGateway).should(never()).approve(any());
	}

	@DisplayName("payment가 이미 SUCCEEDED면 PG 호출 없이 즉시 성공 결과를 반환한다")
	@Test
	void approve_whenPaymentAlreadySucceeded_returnSuccessDirectly() {
		// given
		long memberId = 1L;
		PaymentReservation reservation = createReservation("PAY-1", memberId, 1000);
		Order order = createOrder(1000);
		Payment payment = createPayment("PAY-1", "pg-payment-id", 1000);
		payment.succeed(LocalDateTime.now());

		given(paymentReservationRepository.findByMemberIdAndMerchantPayKey(memberId, "PAY-1")).willReturn(Optional.of(reservation));
		given(orderRepository.findByIdAndMemberId(reservation.getOrderId(), memberId)).willReturn(Optional.of(order));
		given(paymentApprovalRecordService.create(any(PaymentReservation.class), eq("pg-payment-id")))
			.willReturn(payment);

		// when
		NaverPayApproveResponse result = naverPayApprovalUseCase.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(naverPayGateway).should(never()).approve(any());
		then(naverPayGateway).should(never()).getApprovalHistory(any());
	}

	@DisplayName("pgCancel: NaverPayCancelResult.SUCCESS → CancelOutcome.SUCCESS")
	@Test
	void pgCancel_whenResultIsSuccess_returnSuccessOutcome() {
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유")).willReturn(NaverPayCancelResult.success());

		CancelOutcome outcome = ReflectionTestUtils.invokeMethod(naverPayApprovalUseCase, "pgCancel", cancelPayment, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.SUCCESS);
	}

	@DisplayName("pgCancel: NaverPayCancelResult.ALREADY_CANCELED → CancelOutcome.SUCCESS")
	@Test
	void pgCancel_whenResultIsAlreadyCanceled_returnSuccessOutcome() {
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유")).willReturn(NaverPayCancelResult.alreadyCanceled());

		CancelOutcome outcome = ReflectionTestUtils.invokeMethod(naverPayApprovalUseCase, "pgCancel", cancelPayment, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.SUCCESS);
	}

	@DisplayName("pgCancel: NaverPayCancelResult.PROCESSING → CancelOutcome.PROCESSING")
	@Test
	void pgCancel_whenResultIsProcessing_returnProcessingOutcome() {
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유")).willReturn(NaverPayCancelResult.processing());

		CancelOutcome outcome = ReflectionTestUtils.invokeMethod(naverPayApprovalUseCase, "pgCancel", cancelPayment, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.PROCESSING);
	}

	@DisplayName("pgCancel: NaverPayCancelResult.FAILED → CancelOutcome.FAILED with failCode/failDetail")
	@Test
	void pgCancel_whenResultIsFailed_returnFailedOutcomeWithDetails() {
		Payment cancelPayment = Payment.createCancelRequested(1L, "PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유"))
			.willReturn(NaverPayCancelResult.failed(PaymentFailCode.PG_REQUEST_REJECTED, "reject reason"));

		CancelOutcome outcome = ReflectionTestUtils.invokeMethod(naverPayApprovalUseCase, "pgCancel", cancelPayment, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.FAILED);
		assertThat(outcome.failCode()).isEqualTo(PaymentFailCode.PG_REQUEST_REJECTED);
		assertThat(outcome.failDetail()).isEqualTo("reject reason");
	}

	private PaymentReservation createReservation(String merchantPayKey, long memberId, int amount) {
		PaymentReservation r = PaymentReservation.createReserved(
			1L, memberId, amount, PaymentProvider.NAVERPAY, merchantPayKey,
			LocalDateTime.now().plusMinutes(15));
		ReflectionTestUtils.setField(r, "id", 1L);
		return r;
	}

	private PaymentReservation createUsedReservation(String merchantPayKey, long memberId, int amount) {
		PaymentReservation r = PaymentReservation.createReserved(
			1L, memberId, amount, PaymentProvider.NAVERPAY, merchantPayKey,
			LocalDateTime.now().plusMinutes(15));
		r.use();
		ReflectionTestUtils.setField(r, "id", 1L);
		return r;
	}

	private Payment createPayment(String merchantPayKey, String pgPaymentId, int amount) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, amount, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		return Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(1L);
		order.addOrderItem(1L, 1, totalPrice);
		ReflectionTestUtils.setField(order, "id", 1L);
		return order;
	}
}
