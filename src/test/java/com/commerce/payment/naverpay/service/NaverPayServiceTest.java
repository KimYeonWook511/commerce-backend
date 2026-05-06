package com.commerce.payment.naverpay.service;

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
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;
import com.commerce.payment.service.PaymentAttemptService;
import com.commerce.payment.service.PaymentService;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.order.application.OrderQueryService;

@ExtendWith(MockitoExtension.class)
class NaverPayServiceTest {

	@Mock
	private NaverPayClient naverPayClient;

	@Mock
	private PaymentService paymentService;

	@Mock
	private PaymentAttemptService paymentAttemptService;

	@Mock
	private OrderQueryService orderQueryService;

	@InjectMocks
	private NaverPayService naverPayService;

	@DisplayName("이미 생성된 결제가 있으면 기존 결제 결과를 반환한다")
	@Test
	void approve_whenPaymentExists_returnExistingResult() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000,
			PaymentProvider.NAVERPAY);
		attempt.approveSucceed(LocalDateTime.now());
		Payment completed = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			LocalDateTime.now()
		);

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(completed);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(naverPayClient).should(never()).approve(any());
	}

	@DisplayName("승인 응답 코드가 Success면 결제 완료를 반영하고 성공 결과를 반환한다")
	@Test
	void approve_whenApproveResponseIsSuccess_completePayment() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		Payment completed = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			LocalDateTime.now()
		);

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id")).willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any())).willReturn(completed);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(paymentAttemptService).should()
			.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000);
		then(paymentService).should().completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyOnGoing", "SUCCESS"));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		then(paymentService).should(never()).completeApprove(any(), any(), any(), any());
	}

	@DisplayName("승인 응답 코드가 AlreadyComplete면 승인 이력을 조회해 결제 완료를 반영한다")
	@Test
	void approve_whenApproveResponseIsAlreadyComplete_completePayment() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		Payment completed = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			LocalDateTime.now()
		);

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyComplete", "SUCCESS"));
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(buildHistoryResponse("PAY-1", 1000, "SUCCESS", "01"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any())).willReturn(completed);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(paymentService).should().completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyComplete", "SUCCESS"));
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(buildHistoryResponse("OTHER-PAY", 1000, "SUCCESS", "01"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH),
			eq("가맹점 결제 키 불일치"),
			any()
		);
		then(naverPayClient).should(never()).cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("이미 완료된 승인 응답에서 승인 이력 조회 코드가 실패면 매핑된 예외를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCodeIsInvalidMerchant_throwException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyComplete", "SUCCESS"));
		NaverPayResponse<NaverPayHistoryBody> historyResponse = buildHistoryResponse("PAY-1", 1000, "SUCCESS", "01");
		ReflectionTestUtils.setField(historyResponse, "code", "InvalidMerchant");
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(historyResponse);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyComplete", "SUCCESS"));
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(buildHistoryResponse("PAY-1", 1000, "PENDING", "02"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyComplete", "SUCCESS"));
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(buildHistoryResponse("PAY-1", 1000, "CANCELLED", "03"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_CANCELED);
			});
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.ALREADY_CANCELED),
			eq("이미 취소된 결제"),
			any()
		);
	}

	@DisplayName("AlreadyComplete 경로에서 승인 이력이 비어있으면 결제를 찾을 수 없다고 처리한다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryListEmpty_throwNotFound() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "AlreadyComplete", "SUCCESS"));
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(buildEmptyHistoryResponse());

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.NETWORK, "network error"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR);
			});
			then(paymentAttemptService).should().failApproveAttempt(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.SERVER_ERROR, "server error"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_SERVER_ERROR);
			});
		then(paymentAttemptService).should().failApproveAttempt(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
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
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "invalid response"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE);
			});
		then(paymentAttemptService).should().failApproveAttempt(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.PG_INVALID_RESPONSE),
			eq("invalid response"),
			any()
		);
		then(paymentService).should(never()).completeApprove(any(), any(), any(), any());
	}

	@DisplayName("승인 가능 시간이 초과되면 결제 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenTimeExpired_markAttemptFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "TimeExpired", "SUCCESS"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED);
			});
			then(paymentAttemptService).should().failApproveAttempt(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
				eq(PaymentAttemptFailCode.TIME_EXPIRED),
				eq("결제 승인 가능 시간 초과 시 (10분 초과시)"),
				any()
			);
	}

	@DisplayName("PG 점검 코드면 결제 시도를 점검 사유로 실패 기록하고 예외를 던진다")
	@Test
	void approve_whenMaintenanceCode_markAttemptFailedAndThrowMaintenanceException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "MaintenanceOngoing", "SUCCESS"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_MAINTENANCE);
			});
		then(paymentAttemptService).should().failApproveAttempt(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.PG_MAINTENANCE),
			eq("서비스 점검중"),
			any()
		);
	}

	@DisplayName("승인 응답 merchantPayKey가 다르면 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenApproveResponseMerchantPayKeyMismatch_markFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH),
			eq("가맹점 결제 키 불일치"),
			any()
		);
			then(naverPayClient).should(never()).cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("승인 금액이 다르면 네이버페이 취소를 요청하고 예외를 던진다")
	@Test
	void approve_whenAmountMismatch_cancelAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Success", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
			then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("이미 다른 결제가 완료된 주문이면 현재 승인 건을 취소하고 예외를 던진다")
	@Test
	void approve_whenDuplicateApproval_cancelCurrentApprovedPaymentAndThrowException() {
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
			given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(1000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
			given(naverPayClient.approve("pg-payment-id"))
				.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
			given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
				.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE));
			given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
				.willReturn(buildCancelResponse("Success", "pg-payment-id"));

				assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
					.isInstanceOf(PaymentException.class)
					.satisfies(exception -> {
						PaymentException paymentException = (PaymentException)exception;
						assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
					});
				then(paymentAttemptService).should().getOrCreateCancelRequested(
					eq("PAY-1"),
					eq(PaymentProvider.NAVERPAY),
					eq("pg-payment-id"),
					eq(1000)
				);
				then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
				then(paymentAttemptService).should().failApproveAttempt(
					eq("PAY-1"),
					eq(PaymentProvider.NAVERPAY),
					eq("pg-payment-id"),
					eq(PaymentAttemptFailCode.DUPLICATE_PAYMENT),
					eq(PaymentErrorCode.PAYMENT_DUPLICATE.getMessage()),
					any()
				);
			}

	@DisplayName("중복 결제 보상 중 이미 취소 완료된 시도가 있으면 취소 API를 다시 호출하지 않는다")
	@Test
	void approve_whenDuplicateApprovalAndCancelAttemptAlreadySucceeded_skipCancelApiCall() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-1",
			"pg-payment-id",
			1000,
			PaymentProvider.NAVERPAY
		);
		cancelAttempt.cancelSucceed(java.time.LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(1000))).willReturn(cancelAttempt);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
		then(naverPayClient).should(never()).cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("결제 완료 반영 중 merchantPayKey 불일치 예외가 발생하면 승인 시도를 실패로 기록하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsMerchantKeyMismatch_markAttemptFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH),
			eq("가맹점 결제 키 불일치"),
			any()
		);
		then(naverPayClient).should(never()).cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("결제 완료 반영 중 금액 불일치 예외가 발생하면 취소를 요청하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsAmountMismatch_cancelAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		given(paymentAttemptService.getOrCreateCancelRequested(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(1000)
		)).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Success", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("결제 완료 반영 중 주문 예외가 발생하면 취소를 요청하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsOrderException_cancelAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED));
		given(paymentAttemptService.getOrCreateCancelRequested(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(1000)
		)).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Success", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
		then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("결제 완료 반영 중 기타 결제 예외가 발생하면 APPROVE_PROCESS_FAILED로 취소를 요청하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsUnhandledPaymentException_cancelWithApprovalFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED));
		given(paymentAttemptService.getOrCreateCancelRequested(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(1000)
		)).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Success", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
			});
			then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("결제 완료 반영 중 예상하지 못한 예외가 발생하면 APPROVE_PROCESS_FAILED로 취소를 요청하고 예외를 던진다")
	@Test
	void approve_whenCompleteApproveThrowsUnexpectedException_cancelWithApprovalFailedAndThrowException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any()))
			.willThrow(new RuntimeException("db write failed"));
		given(paymentAttemptService.getOrCreateCancelRequested(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(1000)
		)).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Success", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("db write failed");
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.APPROVE_PROCESS_FAILED),
			eq("결제 완료 반영 중 예상치 못한 오류"),
			any()
		);
		then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("다른 사용자의 paymentId로 승인 응답을 받으면 merchantPayKey 불일치로 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPaymentIdReturnsDifferentMerchantPayKey_markFailedWithoutCancel() {
		// given
		long memberId = 1L;
		Order attackerOrder = createOrder(1000);
		attackerOrder.assignMerchantPayKey("PAY-ATTACKER");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-ATTACKER", memberId)).willReturn(attackerOrder);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-ATTACKER")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested(
			"PAY-ATTACKER",
			PaymentProvider.NAVERPAY,
			"pg-victim-payment-id",
			1000
		)).willReturn(PaymentAttempt.createApproveRequested(
			"PAY-ATTACKER",
			"pg-victim-payment-id",
			1000,
			PaymentProvider.NAVERPAY
		));
		given(naverPayClient.approve("pg-victim-payment-id"))
			.willReturn(buildApprovalResponse("PAY-VICTIM", 1000, "Success", "SUCCESS"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-ATTACKER", "pg-victim-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH);
			});
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-ATTACKER"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-victim-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH),
			eq("가맹점 결제 키 불일치"),
			any()
		);
		then(naverPayClient).should(never()).cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("다른 사용자의 paymentId로 AlreadyComplete 응답을 받았고 history merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPaymentIdHistoryReturnsDifferentMerchantPayKey_markFailedWithoutCancel() {
		// given
		long memberId = 1L;
		Order attackerOrder = createOrder(1000);
		attackerOrder.assignMerchantPayKey("PAY-ATTACKER");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-ATTACKER", memberId)).willReturn(attackerOrder);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-ATTACKER")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested(
			"PAY-ATTACKER",
			PaymentProvider.NAVERPAY,
			"pg-victim-payment-id",
			1000
		)).willReturn(PaymentAttempt.createApproveRequested(
			"PAY-ATTACKER",
			"pg-victim-payment-id",
			1000,
			PaymentProvider.NAVERPAY
		));
		given(naverPayClient.approve("pg-victim-payment-id"))
			.willReturn(buildApprovalResponse("PAY-ATTACKER", 1000, "AlreadyComplete", "SUCCESS"));
		given(naverPayClient.getAllHistory("pg-victim-payment-id"))
			.willReturn(buildHistoryResponse("PAY-VICTIM", 1000, "SUCCESS", "01"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-ATTACKER", "pg-victim-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		then(paymentAttemptService).should().failApproveAttempt(
			eq("PAY-ATTACKER"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-victim-payment-id"),
			eq(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH),
			eq("가맹점 결제 키 불일치"),
			any()
		);
		then(naverPayClient).should(never()).cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("취소 응답 코드가 AlreadyCanceled면 취소 시도를 성공으로 기록한다")
	@Test
	void approve_whenAmountMismatchAndCancelResponseAlreadyCanceled_markCancelSucceed() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("AlreadyCanceled", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentAttemptService).should().succeedCancelAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			any()
		);
	}

	@DisplayName("취소 API는 성공했지만 취소 성공 반영에 실패해도 원래 승인 실패 예외를 유지한다")
	@Test
	void approve_whenAmountMismatchAndSucceedCancelAttemptFails_keepOriginalException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Success", "pg-payment-id"));
		org.mockito.Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND))
			.when(paymentAttemptService)
			.succeedCancelAttempt(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), any());

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("취소 응답 코드가 AlreadyOnGoing이면 취소 시도 상태를 변경하지 않는다")
	@Test
	void approve_whenAmountMismatchAndCancelResponseAlreadyOnGoing_keepCancelAttemptRequested() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("AlreadyOnGoing", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentAttemptService).should(never()).succeedCancelAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			any()
		);
			then(paymentAttemptService).should(never()).failCancelAttempt(
				eq("PAY-1"),
				eq(PaymentProvider.NAVERPAY),
				eq("pg-payment-id"),
				any(),
				any(),
				any()
			);
	}

	@DisplayName("취소 응답 코드가 실패면 취소 시도를 실패로 기록한다")
	@Test
	void approve_whenAmountMismatchAndCancelResponseFail_markCancelFailed() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Fail", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentAttemptService).should().failCancelAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.PG_REQUEST_REJECTED),
			eq("기타 실패"),
			any()
		);
	}

	@DisplayName("취소 응답 코드가 InvalidMerchant면 취소 시도를 INVALID_MERCHANT로 기록한다")
	@Test
	void approve_whenAmountMismatchAndCancelResponseInvalidMerchant_markCancelFailedWithInvalidMerchant() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000)))
			.willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("InvalidMerchant", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentAttemptService).should().failCancelAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.INVALID_MERCHANT),
			eq("유효하지 않은 가맹점"),
			any()
		);
	}

	@DisplayName("취소 응답 코드가 CancelNotComplete면 취소 시도를 CANCEL_PROCESS_FAILED로 기록한다")
	@Test
	void approve_whenAmountMismatchAndCancelResponseCancelNotComplete_markCancelFailedWithProcessFail() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000)))
			.willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("CancelNotComplete", "pg-payment-id"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(paymentAttemptService).should().failCancelAttempt(
			eq("PAY-1"),
			eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"),
			eq(PaymentAttemptFailCode.CANCEL_PROCESS_FAILED),
			eq("취소 처리가 완료되지 않았지만, 빠른 시일 내에 자동 취소 재처리 예정."),
			any()
		);
	}

	@DisplayName("취소 API는 실패 응답을 줬지만 취소 실패 반영에 실패해도 원래 승인 실패 예외를 유지한다")
	@Test
	void approve_whenAmountMismatchAndFailCancelAttemptFails_keepOriginalException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000, PaymentProvider.NAVERPAY));
		given(paymentAttemptService.getOrCreateCancelRequested(eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-payment-id"), eq(2000))).willReturn(PaymentAttempt.createCancelRequested("PAY-1", "pg-payment-id", 2000, PaymentProvider.NAVERPAY));
		given(naverPayClient.approve("pg-payment-id"))
			.willReturn(buildApprovalResponse("PAY-1", 2000, "Success", "SUCCESS"));
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class)))
			.willReturn(buildCancelResponse("Fail", "pg-payment-id"));
		org.mockito.Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND))
			.when(paymentAttemptService)
			.failCancelAttempt(
				eq("PAY-1"),
				eq(PaymentProvider.NAVERPAY),
				eq("pg-payment-id"),
				eq(PaymentAttemptFailCode.PG_REQUEST_REJECTED),
				eq("기타 실패"),
				any()
			);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		then(naverPayClient).should().cancel(any(NaverPayCancelRequest.class));
	}

	@DisplayName("같은 결제 시도 이력이 실패 상태면 예외를 던진다")
	@Test
	void approve_whenAttemptFailed_throwException() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000,
			PaymentProvider.NAVERPAY);
		attempt.approveFail(PaymentAttemptFailCode.TIME_EXPIRED, "expired", LocalDateTime.now());

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1")).willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(attempt);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(memberId, "PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED);
			});
			then(naverPayClient).should(never()).approve(any());
	}

	@DisplayName("승인 시도 이력이 성공 상태인데 payment가 없으면 history로 복구 처리한다")
	@Test
	void approve_whenAttemptSucceededAndPaymentMissing_completeByHistory() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		order.assignMerchantPayKey("PAY-1");
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested("PAY-1", "pg-payment-id", 1000,
			PaymentProvider.NAVERPAY);
		attempt.approveSucceed(LocalDateTime.now());
		Payment completed = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			LocalDateTime.now()
		);

		given(orderQueryService.getOrderByMerchantPayKeyAndMemberId("PAY-1", memberId)).willReturn(order);
		given(paymentService.findPaymentByMerchantPayKeyOrNull("PAY-1"))
			.willReturn(null)
			.willReturn(null);
		given(paymentAttemptService.getOrCreateApproveRequested("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id", 1000))
			.willReturn(attempt);
		given(naverPayClient.getAllHistory("pg-payment-id"))
			.willReturn(buildHistoryResponse("PAY-1", 1000, "SUCCESS", "01"));
		given(paymentService.completeApprove(eq("PAY-1"), eq(PaymentProvider.NAVERPAY),
			eq("pg-payment-id"), any())).willReturn(completed);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, "PAY-1", "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(naverPayClient).should(never()).approve(any());
			then(naverPayClient).should().getAllHistory("pg-payment-id");
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

	private NaverPayResponse<NaverPayApproveBody> buildApprovalResponse(
		String merchantPayKey,
		int amount,
		String code,
		String state
	) {
		NaverPayResponse<NaverPayApproveBody> response = new NaverPayResponse<>();
		NaverPayApproveBody body = new NaverPayApproveBody();
		NaverPayApproveBody.Detail detail = new NaverPayApproveBody.Detail();

		ReflectionTestUtils.setField(response, "code", code);
		ReflectionTestUtils.setField(detail, "paymentId", "pg-payment-id");
		ReflectionTestUtils.setField(detail, "merchantPayKey", merchantPayKey);
		ReflectionTestUtils.setField(detail, "admissionState", state);
		ReflectionTestUtils.setField(detail, "totalPayAmount", amount);
		ReflectionTestUtils.setField(body, "detail", detail);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayCancelBody> buildCancelResponse(String code, String paymentId) {
		NaverPayResponse<NaverPayCancelBody> response = new NaverPayResponse<>();
		NaverPayCancelBody body = new NaverPayCancelBody();

		ReflectionTestUtils.setField(response, "code", code);
		ReflectionTestUtils.setField(body, "paymentId", paymentId);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayHistoryBody> buildHistoryResponse(
		String merchantPayKey,
		int totalPayAmount,
		String admissionState,
		String admissionTypeCode
	) {
		NaverPayResponse<NaverPayHistoryBody> response = new NaverPayResponse<>();
		NaverPayHistoryBody body = new NaverPayHistoryBody();
		NaverPayHistoryBody.History history = new NaverPayHistoryBody.History();
		java.util.List<NaverPayHistoryBody.History> list = new java.util.ArrayList<>();

		ReflectionTestUtils.setField(response, "code", "Success");
		ReflectionTestUtils.setField(history, "paymentId", "pg-payment-id");
		ReflectionTestUtils.setField(history, "merchantPayKey", merchantPayKey);
		ReflectionTestUtils.setField(history, "admissionState", admissionState);
		ReflectionTestUtils.setField(history, "admissionTypeCode", admissionTypeCode);
		ReflectionTestUtils.setField(history, "totalPayAmount", totalPayAmount);
		list.add(history);
		ReflectionTestUtils.setField(body, "list", list);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayHistoryBody> buildEmptyHistoryResponse() {
		NaverPayResponse<NaverPayHistoryBody> response = new NaverPayResponse<>();
		NaverPayHistoryBody body = new NaverPayHistoryBody();

		ReflectionTestUtils.setField(response, "code", "Success");
		ReflectionTestUtils.setField(body, "list", new java.util.ArrayList<>());
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}
}
