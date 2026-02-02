package com.commerce.payment.naverpay.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.commerce.auth.filter.JwtAuthenticationFilter;
import com.commerce.auth.interceptor.AuthorizationInterceptor;
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.auth.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.config.WebConfig;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.service.NaverPayService;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;

@WebMvcTest(NaverPayController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class NaverPayControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private NaverPayService naverPayService;

	@MockitoBean
	private JwtTokenValidator jwtTokenValidator;

	@DisplayName("결제 승인 성공이면 성공 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenSuccess_redirectToSuccess() throws Exception {
		// given
		NaverPayApproveResult result = NaverPayApproveResult.builder()
			.orderId(1L)
			.pgPaymentId("pg-payment-id")
			.status(NaverPayApproveStatus.SUCCESS)
			.build();
		given(naverPayService.approve("PAY-1", "pg-payment-id")).willReturn(result);

		// when & then
		mockMvc.perform(get("/payments/naverpay/return/PAY-1")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/1/payment/success"));
	}

	@DisplayName("결제 승인 처리 중이면 처리중 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenProcessing_redirectToProcessing() throws Exception {
		// given
		NaverPayApproveResult result = NaverPayApproveResult.builder()
			.orderId(1L)
			.pgPaymentId(null)
			.status(NaverPayApproveStatus.PROCESSING)
			.build();
		given(naverPayService.approve("PAY-1", "pg-payment-id")).willReturn(result);

		// when & then
		mockMvc.perform(get("/payments/naverpay/return/PAY-1")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/1/payment/processing"));
	}

	@DisplayName("결제 승인 실패면 실패 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenFail_redirectToFail() throws Exception {
		// given
		NaverPayApproveResult result = NaverPayApproveResult.builder()
			.orderId(1L)
			.pgPaymentId(null)
			.status(NaverPayApproveStatus.FAIL)
			.build();
		given(naverPayService.approve("PAY-1", "pg-payment-id")).willReturn(result);

		// when & then
		mockMvc.perform(get("/payments/naverpay/return/PAY-1")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/payment/fail"));
	}

	@DisplayName("결제 승인 실패 코드면 실패 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenResultCodeFail_redirectToFail() throws Exception {
		// when & then
		mockMvc.perform(get("/payments/naverpay/return/PAY-1")
				.param("resultCode", "Fail")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/payment/fail"));
	}

	@DisplayName("결제 승인 처리 중 예외가 발생하면 실패 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenApproveFails_redirectToFail() throws Exception {
		// given
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED))
			.given(naverPayService).approve("PAY-1", "pg-payment-id");

		// when & then
		mockMvc.perform(get("/payments/naverpay/return/PAY-1")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/payment/fail"));
	}
}
