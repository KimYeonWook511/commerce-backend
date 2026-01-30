package com.commerce.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.service.PaymentService;
import com.commerce.payment.service.request.PaymentReadyServiceRequest;
import com.commerce.payment.service.response.PaymentReadyResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class PaymentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PaymentService paymentService;

	@MockitoBean
	private NaverPayService naverPayService;

	@MockitoBean
	private JwtTokenValidator jwtTokenValidator;

	@DisplayName("결제 준비 요청이 유효하면 결제 준비 응답을 반환한다")
	@Test
	void readyPayment_whenValidRequest_returnOk() throws Exception {
		// given
		stubForValidToken();

		PaymentReadyResponse response = PaymentReadyResponse.builder()
			.clientId("clientId")
			.chainId("chainId")
			.merchantPayKey("PAY-1")
			.productName("상품명")
			.productCount(1)
			.totalPayAmount(1000)
			.taxScopeAmount(1000)
			.taxExScopeAmount(0)
			.returnUrl("https://return-url")
			.build();
		given(paymentService.readyPayment(any(PaymentReadyServiceRequest.class)))
			.willReturn(response);

		String requestBody = """
			{
			  "orderId": 1,
			  "provider": "NAVERPAY"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/ready")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.clientId").value("clientId"))
			.andExpect(jsonPath("$.data.chainId").value("chainId"))
			.andExpect(jsonPath("$.data.merchantPayKey").value("PAY-1"))
			.andExpect(jsonPath("$.data.productName").value("상품명"))
			.andExpect(jsonPath("$.data.productCount").value(1))
			.andExpect(jsonPath("$.data.totalPayAmount").value(1000))
			.andExpect(jsonPath("$.data.taxScopeAmount").value(1000))
			.andExpect(jsonPath("$.data.taxExScopeAmount").value(0))
			.andExpect(jsonPath("$.data.returnUrl").value("https://return-url"));
	}

	@DisplayName("지원하지 않는 결제 수단이면 요청이 실패한다")
	@Test
	void readyPayment_whenProviderInvalid_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "orderId": 1,
			  "provider": "INVALID"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/ready")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT-400-1"))
			.andExpect(jsonPath("$.message").value("지원하지 않는 결제 수단입니다"))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@DisplayName("결제 승인 성공이면 성공 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenSuccess_redirectToSuccess() throws Exception {
		// given
		NaverPayApproveResult result = NaverPayApproveResult.builder()
			.orderId(1L)
			.pgPaymentId("pg-payment-id")
			.status(PaymentStatus.COMPLETED)
			.build();
		given(naverPayService.approve("pg-payment-id")).willReturn(result);

		// when & then
		mockMvc.perform(get("/payments/naverpay/return")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/1/payment/success"));
	}

	@DisplayName("결제 승인 실패 코드면 실패 페이지로 리다이렉트한다")
	@Test
	void approveNaverPay_whenResultCodeFail_redirectToFail() throws Exception {
		// when & then
		mockMvc.perform(get("/payments/naverpay/return")
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
			.given(naverPayService).approve("pg-payment-id");

		// when & then
		mockMvc.perform(get("/payments/naverpay/return")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/orders/payment/fail"));
	}

	private void stubForValidToken() {
		Claims claims = Jwts.claims().setSubject("1");
		claims.put("role", "ROLE_USER");
		given(jwtTokenValidator.validateAccessToken("access-token")).willReturn(claims);
	}
}
