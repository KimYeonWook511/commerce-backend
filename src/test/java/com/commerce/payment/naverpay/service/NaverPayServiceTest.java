package com.commerce.payment.naverpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;
import com.commerce.payment.service.PaymentService;
import com.commerce.product.domain.Product;

@ExtendWith(MockitoExtension.class)
class NaverPayServiceTest {

	@Mock
	private NaverPayClient naverPayClient;

	@Mock
	private PaymentService paymentService;

	@InjectMocks
	private NaverPayService naverPayService;

	@DisplayName("결제 승인에 성공하면 결제 완료 결과를 반환한다")
	@Test
	void approve_whenSuccess_returnResult() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();

		NaverPayResponse<NaverPayApproveBody> response = buildApprovalResponse(merchantPayKey, 1000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.completePayment(eq(merchantPayKey), eq("pg-payment-id"), any()))
			.willReturn(completedFrom(pending, "pg-payment-id"));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
	}

	@DisplayName("네이버페이 서버 오류면 재시도 가능한 실패로 처리한다")
	@Test
	void approve_whenRetryableException_returnRetryableFail() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.SERVER_ERROR, "server error"));
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED.getMessage());
	}

	@DisplayName("네이버페이 네트워크 오류면 재시도 가능한 실패로 처리한다")
	@Test
	void approve_whenNetworkException_returnRetryableFail() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.NETWORK, "network error"));
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_RETRYABLE_FAILED.getMessage());
	}

	@DisplayName("네이버페이 응답 오류면 실패로 처리한다")
	@Test
	void approve_whenNonRetryableException_returnFail() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "invalid response"));
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());
	}

	@DisplayName("네이버페이 인증 오류면 실패로 처리한다")
	@Test
	void approve_whenAuthenticationException_returnFail() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.AUTHENTICATION, "auth error"));
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());
	}

	@DisplayName("네이버페이 클라이언트 오류면 실패로 처리한다")
	@Test
	void approve_whenClientErrorException_returnFail() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(naverPayClient.approve("pg-payment-id"))
			.willThrow(new NaverPayException(NaverPayErrorCode.CLIENT_ERROR, "client error"));
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());
	}

	@DisplayName("처리 중인 결제면 외부 호출 없이 결과를 반환한다")
	@Test
	void approve_whenProcessing_returnResult() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		ReflectionTestUtils.setField(payment, "pgPaymentId", "pg-payment-id");
		String merchantPayKey = payment.getMerchantPayKey();

		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(payment);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
	}

	@DisplayName("이미 처리 중이면 최신 결제 상태를 반환한다")
	@Test
	void approve_whenMarkProcessingReturnsZero_returnResult() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		Payment processing = processingFrom(pending);
		String merchantPayKey = pending.getMerchantPayKey();

		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId))
			.willReturn(pending, processing);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(0);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
	}

	@DisplayName("결제 금액이 다르면 결제 승인에 실패한다")
	@Test
	void approve_whenAmountMismatch_returnFail() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();

		NaverPayResponse<NaverPayApproveBody> response = buildApprovalResponse(merchantPayKey, 2000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(merchantPayKey, null, PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH.getMessage());
	}

	@DisplayName("결제 키가 다르면 결제 승인에 실패한다")
	@Test
	void approve_whenMerchantPayKeyMismatch_returnFail() {
		// given
		long memberId = 1L;
		Order order = createOrder(1000);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();

		NaverPayResponse<NaverPayApproveBody> response = buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.getPaymentByMerchantPayKey("OTHER-PAY")).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		// when
		assertThatThrownBy(() -> naverPayService.approve(memberId, merchantPayKey, "pg-payment-id"))
			.isInstanceOf(PaymentException.class);

		// then
		then(paymentService).should(never()).failPayment(any(), any(), any());
	}

	@DisplayName("이미 진행 중이면 결제 처리 상태를 반환한다")
	@Test
	void approve_whenAlreadyOnGoing_returnProcessing() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		NaverPayResponse<NaverPayApproveBody> response =
			buildApprovalResponse(merchantPayKey, 1000, "AlreadyOnGoing", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId))
			.willReturn(pending, processingFrom(pending));
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
	}

	@DisplayName("이미 완료된 결제라면 완료 상태를 반환한다")
	@Test
	void approve_whenAlreadyComplete_returnSuccess() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		NaverPayResponse<NaverPayApproveBody> response =
			buildApprovalResponse(merchantPayKey, 1000, "AlreadyComplete", "SUCCESS");
		Payment completed = completedFrom(pending, "pg-payment-id");

		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId))
			.willReturn(pending, completed);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		then(paymentService).should(never()).completePayment(any(), any(), any());
	}

	@DisplayName("승인 코드가 시간 초과면 실패 처리한다")
	@Test
	void approve_whenTimeExpired_returnFail() {
		// given
		long memberId = 1L;
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		NaverPayResponse<NaverPayApproveBody> response =
			buildApprovalResponse(merchantPayKey, 1000, "TimeExpired", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKeyAndMemberId(merchantPayKey, memberId)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_TIME_EXPIRED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(memberId, merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_TIME_EXPIRED.getMessage());
	}

	@DisplayName("결제 요청 실패 코드면 결제를 실패 처리한다")
	@Test
	void failByResultCode_whenPending_returnFail() {
		// given
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.failByResultCode(merchantPayKey, "Fail", "fail-message");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());
	}

	@DisplayName("사용자 취소 코드면 사용자 취소로 실패 처리한다")
	@Test
	void failByResultCode_whenUserCancel_returnUserCanceled() {
		// given
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_USER_CANCELED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result =
			naverPayService.failByResultCode(merchantPayKey, "UserCancel", "user canceled");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_USER_CANCELED.getMessage());
	}

	@DisplayName("시간 초과 코드면 시간 초과로 실패 처리한다")
	@Test
	void failByResultCode_whenTimeExpired_returnTimeExpired() {
		// given
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_TIME_EXPIRED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result =
			naverPayService.failByResultCode(merchantPayKey, "TimeExpired", "expired");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
		then(paymentService).should().failPayment(
			merchantPayKey, null, PaymentErrorCode.PAYMENT_TIME_EXPIRED.getMessage());
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(createMember());
		Product product = Product.builder()
			.name("product")
			.price(totalPrice)
			.build();
		order.addOrderItem(product, 1);
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
		String merchantPayKey, int amount, String code, String state
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

	private Payment processingFrom(Payment pending) {
		Payment processing = Payment.create(pending.getOrder(), pending.getAmount(), pending.getProvider());
		ReflectionTestUtils.setField(processing, "merchantPayKey", pending.getMerchantPayKey());
		ReflectionTestUtils.setField(processing, "status", PaymentStatus.PROCESSING);
		return processing;
	}

	private Payment completedFrom(Payment pending, String pgPaymentId) {
		Payment completed = Payment.create(pending.getOrder(), pending.getAmount(), pending.getProvider());
		ReflectionTestUtils.setField(completed, "merchantPayKey", pending.getMerchantPayKey());
		ReflectionTestUtils.setField(completed, "status", PaymentStatus.COMPLETED);
		ReflectionTestUtils.setField(completed, "pgPaymentId", pgPaymentId);
		ReflectionTestUtils.setField(completed, "approvedAt", java.time.LocalDateTime.now());
		return completed;
	}

	private Payment failedFrom(Payment pending) {
		Payment failed = Payment.create(pending.getOrder(), pending.getAmount(), pending.getProvider());
		ReflectionTestUtils.setField(failed, "merchantPayKey", pending.getMerchantPayKey());
		ReflectionTestUtils.setField(failed, "status", PaymentStatus.FAILED);
		ReflectionTestUtils.setField(failed, "failureReason", PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());
		return failed;
	}
}
