package com.commerce.payment.legacy.naverpay.presentation.http;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.commerce.common.security.filter.TokenAuthenticationFilter;
import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.port.TokenValidator;
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.payment.legacy.naverpay.application.usecase.ApproveNaverPayUseCase;
import com.commerce.payment.legacy.naverpay.application.dto.NaverPayApproveResponse;
import com.commerce.payment.legacy.naverpay.application.dto.NaverPayApproveStatus;


@WebMvcTest(NaverPayController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	SecurityWebMvcConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	TokenAuthenticationFilter.class
})
class NaverPayControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ApproveNaverPayUseCase approveNaverPayUseCase;

	@MockitoBean
	private TokenValidator tokenValidator;

	@DisplayName("결제 결과 요청은 정상적으로 응답한다")
	@Test
	void returnFromNaverPay_whenRequestReceived_returnOk() throws Exception {
		// when & then
		mockMvc.perform(get("/payments/naverpay/return")
				.param("merchantPayKey", "PAY-1")
				.param("resultCode", "Fail")
				.param("resultMessage", "fail-message")
				.param("paymentId", "pg-payment-id"))
			.andExpect(status().isOk());
	}

	@DisplayName("결제 승인 요청이 성공하면 결과를 반환한다")
	@Test
	void approveNaverPay_whenSuccess_returnOk() throws Exception {
		// given
		stubForValidToken();
		NaverPayApproveResponse response = NaverPayApproveResponse.builder()
			.pgPaymentId("pg-payment-id")
			.status(NaverPayApproveStatus.SUCCESS)
			.build();
		given(approveNaverPayUseCase.approve(1L, "PAY-1", "pg-payment-id")).willReturn(response);

		String requestBody = """
			{
			  "merchantPayKey": "PAY-1",
			  "paymentId": "pg-payment-id"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/naverpay/approve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.pgPaymentId").value("pg-payment-id"))
			.andExpect(jsonPath("$.data.status").value("SUCCESS"));

		then(approveNaverPayUseCase).should().approve(1L, "PAY-1", "pg-payment-id");
	}

	@DisplayName("merchantPayKey가 비어있으면 요청 값 검증에 실패한다")
	@Test
	void approveNaverPay_whenMerchantPayKeyIsBlank_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "merchantPayKey": " ",
			  "paymentId": "pg-payment-id"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/naverpay/approve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.data.merchantPayKey").exists());
	}

	@DisplayName("paymentId가 비어있으면 요청 값 검증에 실패한다")
	@Test
	void approveNaverPay_whenPaymentIdIsBlank_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "merchantPayKey": "PAY-1",
			  "paymentId": " "
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/naverpay/approve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.data.paymentId").exists());
	}

	private void stubForValidToken() {
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));
	}
}
