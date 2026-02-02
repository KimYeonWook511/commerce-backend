package com.commerce.payment.naverpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

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
import com.commerce.payment.naverpay.client.response.NaverPayApproveResponse;
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
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();

		NaverPayApproveResponse response = buildApprovalResponse(merchantPayKey, 1000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.completePayment(eq(merchantPayKey), eq("pg-payment-id"), any()))
			.willReturn(completedFrom(pending, "pg-payment-id"));

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getOrderId()).isEqualTo(1L);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
	}

	@DisplayName("응답이 비어있으면 결제 승인에 실패한다")
	@Test
	void approve_whenResponseNull_returnFail() {
		// given
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		given(naverPayClient.approve("pg-payment-id")).willReturn(null);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
	}

	@DisplayName("승인 코드가 Success가 아니면 결제 승인에 실패한다")
	@Test
	void approve_whenCodeNotSuccess_returnFail() {
		// given
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		NaverPayApproveResponse response = buildApprovalResponse(merchantPayKey, 1000, "Fail", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
	}

	@DisplayName("승인 상태가 SUCCESS가 아니면 결제 승인에 실패한다")
	@Test
	void approve_whenAdmissionStateNotSuccess_returnFail() {
		// given
		Payment pending = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();
		NaverPayApproveResponse response = buildApprovalResponse(merchantPayKey, 1000, "Success", "FAIL");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
	}

	@DisplayName("결제 정보가 없으면 결제 승인에 실패한다")
	@Test
	void approve_whenPaymentNotFound_throwException() {
		// given
		given(paymentService.getPaymentByMerchantPayKey("PAY-1"))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve("PAY-1", "pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("처리 중인 결제면 외부 호출 없이 결과를 반환한다")
	@Test
	void approve_whenProcessing_returnResult() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		ReflectionTestUtils.setField(payment, "pgPaymentId", "pg-payment-id");
		String merchantPayKey = payment.getMerchantPayKey();

		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(payment);

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(result.getOrderId()).isEqualTo(1L);
	}

	@DisplayName("이미 처리 중이면 최신 결제 상태를 반환한다")
	@Test
	void approve_whenMarkProcessingReturnsZero_returnResult() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		Payment processing = processingFrom(pending);
		String merchantPayKey = pending.getMerchantPayKey();

		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey))
			.willReturn(pending, processing);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(0);

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(result.getOrderId()).isEqualTo(1L);
	}

	@DisplayName("결제 금액이 다르면 결제 승인에 실패한다")
	@Test
	void approve_whenAmountMismatch_returnFail() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();

		NaverPayApproveResponse response = buildApprovalResponse(merchantPayKey, 2000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(merchantPayKey, null, PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
	}

	@DisplayName("결제 키가 다르면 결제 승인에 실패한다")
	@Test
	void approve_whenMerchantPayKeyMismatch_returnFail() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment pending = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = pending.getMerchantPayKey();

		NaverPayApproveResponse response = buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentService.getPaymentByMerchantPayKey(merchantPayKey)).willReturn(pending);
		given(paymentService.markProcessing(merchantPayKey)).willReturn(1);
		given(paymentService.failPayment(merchantPayKey, null, PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH.getMessage()))
			.willReturn(failedFrom(pending));

		// when
		NaverPayApproveResult result = naverPayService.approve(merchantPayKey, "pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.FAIL);
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

	private void setOrderId(Order order, Long orderId) {
		ReflectionTestUtils.setField(order, "id", orderId);
	}

	private NaverPayApproveResponse buildApprovalResponse(String merchantPayKey, int amount, String code, String state) {
		NaverPayApproveResponse response = new NaverPayApproveResponse();
		NaverPayApproveResponse.Body body = new NaverPayApproveResponse.Body();
		NaverPayApproveResponse.Detail detail = new NaverPayApproveResponse.Detail();

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
