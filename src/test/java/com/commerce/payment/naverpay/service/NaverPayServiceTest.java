package com.commerce.payment.naverpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.NaverPayApproveResponse;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.product.domain.Product;

@ExtendWith(MockitoExtension.class)
class NaverPayServiceTest {

	@Mock
	private NaverPayClient naverPayClient;

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private NaverPayService naverPayService;

	@DisplayName("결제 승인에 성공하면 결제 완료 결과를 반환한다")
	@Test
	void approve_whenSuccess_returnResult() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = payment.getMerchantPayKey();

		NaverPayApproveResponse response = buildApprovalResponse(merchantPayKey, 1000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentRepository.findByMerchantPayKey(merchantPayKey)).willReturn(Optional.of(payment));

		// when
		NaverPayApproveResult result = naverPayService.approve("pg-payment-id");

		// then
		assertThat(result.getOrderId()).isEqualTo(1L);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getApprovedAt()).isNotNull();
	}

	@DisplayName("응답이 비어있으면 결제 승인에 실패한다")
	@Test
	void approve_whenResponseNull_throwException() {
		// given
		given(naverPayClient.approve("pg-payment-id")).willReturn(null);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve("pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			});
	}

	@DisplayName("승인 코드가 Success가 아니면 결제 승인에 실패한다")
	@Test
	void approve_whenCodeNotSuccess_throwException() {
		// given
		NaverPayApproveResponse response = buildApprovalResponse("PAY-1", 1000, "Fail", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve("pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			});
	}

	@DisplayName("승인 상태가 SUCCESS가 아니면 결제 승인에 실패한다")
	@Test
	void approve_whenAdmissionStateNotSuccess_throwException() {
		// given
		NaverPayApproveResponse response = buildApprovalResponse("PAY-1", 1000, "Success", "FAIL");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve("pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			});
	}

	@DisplayName("결제 정보가 없으면 결제 승인에 실패한다")
	@Test
	void approve_whenPaymentNotFound_throwException() {
		// given
		NaverPayApproveResponse response = buildApprovalResponse("PAY-1", 1000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> naverPayService.approve("pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("결제 금액이 다르면 결제 승인에 실패한다")
	@Test
	void approve_whenAmountMismatch_throwException() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		String merchantPayKey = payment.getMerchantPayKey();

		NaverPayApproveResponse response = buildApprovalResponse(merchantPayKey, 2000, "Success", "SUCCESS");
		given(naverPayClient.approve("pg-payment-id")).willReturn(response);
		given(paymentRepository.findByMerchantPayKey(merchantPayKey)).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve("pg-payment-id"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
			});
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
}
