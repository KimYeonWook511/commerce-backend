package com.commerce.payment.naverpay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.application.PaymentApprovalAttemptService;
import com.commerce.payment.application.PaymentApprovalCompensationService;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.naverpay.application.result.NaverPayApproveResponse;
import com.commerce.payment.naverpay.application.result.NaverPayApproveStatus;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.order.application.OrderQueryService;

@ExtendWith(MockitoExtension.class)
class NaverPayApprovalServiceTest {

	@Mock
	private NaverPayGateway naverPayGateway;

	@Mock
	private PaymentApprovalService paymentApprovalService;

	@Mock
	private PaymentApprovalAttemptService paymentApprovalAttemptService;

	@Mock
	private PaymentApprovalCompensationService paymentApprovalCompensationService;

	@Mock
	private OrderQueryService orderQueryService;

	@InjectMocks
	private NaverPayApprovalService naverPayApprovalService;

	@DisplayName("이미 생성된 결제가 있으면 기존 결제 결과를 반환한다")
	@Test
	void approve_whenPaymentExists_returnExistingResult() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000,
			PaymentProvider.NAVERPAY);
		attempt.succeed(LocalDateTime.now());
		Payment completed = Payment.createCompleted(
			order, PaymentProvider.NAVERPAY, "PAY-1", "pg-payment-id", LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.of(completed));

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(naverPayGateway).should(never()).approve(any());
	}

	@DisplayName("승인 응답 코드가 Success면 결제 완료를 반영하고 성공 결과를 반환한다")
	@Test
	void approve_whenApproveResponseIsSuccess_completePayment() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		Payment completed = Payment.createCompleted(
			order, PaymentProvider.NAVERPAY, "PAY-1", "pg-payment-id", LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any())).willReturn(completed);

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(paymentApprovalAttemptService).should()
			.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000);
		then(paymentApprovalService).should().completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any());
	}

	@DisplayName("이미 진행 중이면 처리 중 상태를 반환한다")
	@Test
	void approve_whenAlreadyOnGoing_returnProcessing() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.processing());

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(paymentApprovalService).should(never()).completeApprovedPayment(any(), any(), any(), any());
	}

	@DisplayName("승인 응답 코드가 AlreadyComplete면 승인 이력을 조회해 결제 완료를 반영한다")
	@Test
	void approve_whenApproveResponseIsAlreadyComplete_completePayment() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		Payment completed = Payment.createCompleted(
			order, PaymentProvider.NAVERPAY, "PAY-1", "pg-payment-id", LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any())).willReturn(completed);

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(paymentApprovalService).should().completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any());
	}

	@DisplayName("AlreadyComplete 경로에서 history merchantPayKey가 다르면 approve attempt를 실패 처리하고 PAYMENT_NOT_FOUND를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryMerchantPayKeyMismatch_markFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH), eq("가맹점 결제 키 불일치"), any());
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("이미 완료된 승인 응답에서 승인 이력 조회 코드가 실패면 매핑된 예외를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCodeIsInvalidMerchant_throwException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_INVALID_MERCHANT));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
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
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("AlreadyComplete 경로에서 history가 취소 완료 상태면 approve attempt를 ALREADY_CANCELED로 실패 처리하고 PAYMENT_ALREADY_CANCELED를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCanceled_markAlreadyCanceledAndThrowAlreadyCanceled() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id")).willReturn(NaverPayHistoryResult.canceled());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.ALREADY_CANCELED), eq("이미 취소된 결제"), any());
	}

	@DisplayName("AlreadyComplete 경로에서 승인 이력이 비어있으면 결제를 찾을 수 없다고 처리한다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryListEmpty_throwNotFound() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("네트워크 오류면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenNetworkException_markAttemptFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_NETWORK_ERROR, PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR, "network error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), eq(PaymentAttemptFailCode.PG_NETWORK_ERROR), eq("network error"), any());
	}

	@DisplayName("서버 오류면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenServerException_markAttemptFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_SERVER_ERROR, PaymentErrorCode.PAYMENT_PG_SERVER_ERROR, "server error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_SERVER_ERROR);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), eq(PaymentAttemptFailCode.PG_SERVER_ERROR), eq("server error"), any());
	}

	@DisplayName("응답 파싱 오류면 결제 시도만 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenInvalidResponse_markAttemptFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_INVALID_RESPONSE, PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE, "invalid response"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.PG_INVALID_RESPONSE), eq("invalid response"), any());
		then(paymentApprovalService).should(never()).completeApprovedPayment(any(), any(), any(), any());
	}

	@DisplayName("승인 가능 시간이 초과되면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenTimeExpired_markAttemptFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.TIME_EXPIRED, PaymentErrorCode.PAYMENT_TIME_EXPIRED, "결제 승인 가능 시간 초과 시 (10분 초과시)"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.TIME_EXPIRED), eq("결제 승인 가능 시간 초과 시 (10분 초과시)"), any());
	}

	@DisplayName("PG 점검 코드면 결제 시도를 점검 사유로 실패 기록하고 예외를 던진다")
	@Test
	void approve_whenMaintenanceCode_markAttemptFailedAndThrowMaintenanceException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_MAINTENANCE, PaymentErrorCode.PAYMENT_PG_MAINTENANCE, "서비스 점검중"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_MAINTENANCE);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.PG_MAINTENANCE), eq("서비스 점검중"), any());
	}

	@DisplayName("승인 응답 merchantPayKey가 다르면 compensateMerchantKeyMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenApproveResponseMerchantPayKeyMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id"))
			.willReturn(NaverPayApproveResult.success("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentApprovalCompensationService).should().compensateMerchantKeyMismatch(any());
	}

	@DisplayName("승인 금액이 다르면 compensateAmountMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenAmountMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 2000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentApprovalCompensationService).should().compensateAmountMismatch(any(), eq(2000), any());
	}

	@DisplayName("이미 다른 결제가 완료된 주문이면 compensateDuplicatePayment를 호출하고 예외를 던진다")
	@Test
	void approve_whenDuplicateApproval_callsCompensateAndThrowException() {
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE));

		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
		then(paymentApprovalCompensationService).should().compensateDuplicatePayment(any(), any(), any());
	}

	@DisplayName("결제 완료 반영 중 merchantPayKey 불일치 예외가 발생하면 compensateMerchantKeyMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsMerchantKeyMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentApprovalCompensationService).should().compensateMerchantKeyMismatch(any());
	}

	@DisplayName("결제 완료 반영 중 금액 불일치 예외가 발생하면 compensateAmountMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsAmountMismatch_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentApprovalCompensationService).should().compensateAmountMismatch(any(), eq(1000), any());
	}

	@DisplayName("결제 완료 반영 중 주문 예외가 발생하면 compensateUnexpected를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsOrderException_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
		then(paymentApprovalCompensationService).should().compensateUnexpected(
			any(), any(), eq(PaymentAttemptFailCode.APPROVE_PROCESS_FAILED), any());
	}

	@DisplayName("결제 완료 반영 중 기타 결제 예외가 발생하면 APPROVE_PROCESS_FAILED로 compensateUnexpected를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsUnhandledPaymentException_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
			});
		then(paymentApprovalCompensationService).should().compensateUnexpected(
			any(), any(), eq(PaymentAttemptFailCode.APPROVE_PROCESS_FAILED), any());
	}

	@DisplayName("결제 완료 반영 중 예상하지 못한 예외가 발생하면 APPROVE_PROCESS_FAILED로 compensateUnexpected를 호출하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsUnexpectedException_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-payment-id")).willReturn(NaverPayApproveResult.success("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new RuntimeException("db write failed"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("db write failed");
		then(paymentApprovalCompensationService).should().compensateUnexpected(
			any(), any(), eq(PaymentAttemptFailCode.APPROVE_PROCESS_FAILED), any());
	}

	@DisplayName("다른 사용자의 paymentId로 승인 응답을 받으면 compensateMerchantKeyMismatch를 호출하고 예외를 던진다")
	@Test
	void approve_whenForeignPaymentIdReturnsDifferentMerchantPayKey_callsCompensateAndThrowException() {
		// given
		long memberId = 1L;
		Order attackerOrder = createOrder(1000);
		attackerOrder.assignMerchantPayKey("PAY-ATTACKER");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-ATTACKER", memberId)).willReturn(attackerOrder);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-ATTACKER")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate(
			"PAY-ATTACKER", PaymentProvider.NAVERPAY, "pg-victim-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested(
				"PAY-ATTACKER", "pg-victim-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-victim-payment-id"))
			.willReturn(NaverPayApproveResult.success("PAY-VICTIM", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-ATTACKER", "pg-victim-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentApprovalCompensationService).should().compensateMerchantKeyMismatch(any());
	}

	@DisplayName("다른 사용자의 paymentId로 AlreadyComplete 응답을 받았고 history merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPaymentIdHistoryReturnsDifferentMerchantPayKey_markFailedWithoutCancel() {
		// given
		long memberId = 1L;
		Order attackerOrder = createOrder(1000);
		attackerOrder.assignMerchantPayKey("PAY-ATTACKER");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-ATTACKER", memberId)).willReturn(attackerOrder);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-ATTACKER")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate(
			"PAY-ATTACKER", PaymentProvider.NAVERPAY, "pg-victim-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested(
				"PAY-ATTACKER", "pg-victim-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayGateway.approve("pg-victim-payment-id")).willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-victim-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("PAY-VICTIM", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-ATTACKER", "pg-victim-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		then(paymentApprovalAttemptService).should().failIfRequested(
			eq("PAY-ATTACKER"), eq(PaymentProvider.NAVERPAY), eq("pg-victim-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH), eq("가맹점 결제 키 불일치"), any());
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("같은 결제 시도 이력이 실패 상태면 예외를 던진다")
	@Test
	void approve_whenAttemptFailed_throwException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY);
		attempt.fail(PaymentAttemptFailCode.TIME_EXPIRED, "expired", LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(attempt);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED);
			});
		then(naverPayGateway).should(never()).approve(any());
	}

	@DisplayName("승인 시도 이력이 성공 상태인데 payment가 없으면 history로 복구 처리한다")
	@Test
	void approve_whenAttemptSucceededAndPaymentMissing_completeByHistory() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY);
		attempt.succeed(LocalDateTime.now());
		Payment completed = Payment.createCompleted(
			order, PaymentProvider.NAVERPAY, "PAY-1", "pg-payment-id", LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentApprovalService.findPaymentByMerchantPayKey("PAY-1"))
			.willReturn(Optional.empty())
			.willReturn(Optional.empty());
		given(paymentApprovalAttemptService.getOrCreate("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(attempt);
		given(naverPayGateway.getApprovalHistory("pg-payment-id"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(paymentApprovalService.completeApprovedPayment(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any())).willReturn(completed);

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(naverPayGateway).should(never()).approve(any());
		then(naverPayGateway).should().getApprovalHistory("pg-payment-id");
	}

	@DisplayName("pgCancel: NaverPayCancelResult.SUCCESS → CancelOutcome.SUCCESS")
	@Test
	void pgCancel_whenResultIsSuccess_returnSuccessOutcome() {
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유")).willReturn(NaverPayCancelResult.success());

		CancelOutcome outcome = naverPayApprovalService.pgCancel(cancelAttempt, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.SUCCESS);
	}

	@DisplayName("pgCancel: NaverPayCancelResult.ALREADY_CANCELED → CancelOutcome.SUCCESS")
	@Test
	void pgCancel_whenResultIsAlreadyCanceled_returnSuccessOutcome() {
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유")).willReturn(NaverPayCancelResult.alreadyCanceled());

		CancelOutcome outcome = naverPayApprovalService.pgCancel(cancelAttempt, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.SUCCESS);
	}

	@DisplayName("pgCancel: NaverPayCancelResult.PROCESSING → CancelOutcome.PROCESSING")
	@Test
	void pgCancel_whenResultIsProcessing_returnProcessingOutcome() {
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유")).willReturn(NaverPayCancelResult.processing());

		CancelOutcome outcome = naverPayApprovalService.pgCancel(cancelAttempt, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.PROCESSING);
	}

	@DisplayName("pgCancel: NaverPayCancelResult.FAILED → CancelOutcome.FAILED with failCode/failDetail")
	@Test
	void pgCancel_whenResultIsFailed_returnFailedOutcomeWithDetails() {
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested("PAY-1", "pg-id", 1000, PaymentProvider.NAVERPAY);
		given(naverPayGateway.cancel("pg-id", 1000, "취소 사유"))
			.willReturn(NaverPayCancelResult.failed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "reject reason"));

		CancelOutcome outcome = naverPayApprovalService.pgCancel(cancelAttempt, "취소 사유");

		assertThat(outcome.status()).isEqualTo(CancelOutcome.Status.FAILED);
		assertThat(outcome.failCode()).isEqualTo(PaymentAttemptFailCode.PG_REQUEST_REJECTED);
		assertThat(outcome.failDetail()).isEqualTo("reject reason");
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(createMember());
		Product product = Product.builder()
			.name("product")
			.price(totalPrice)
			.status(ProductStatus.ON_SALE)
			.build();
		order.addOrderItem(product, 1);
		ReflectionTestUtils.setField(order, "id", 1L);
		return order;
	}

	private Member createMember() {
		return Member.builder()
			.email("payment@example.com")
			.password("password123")
			.username("payer")
			.build();
	}
}
