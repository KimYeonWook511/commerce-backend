package com.commerce.payment.presentation.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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

import com.commerce.security.filter.JwtAuthenticationFilter;
import com.commerce.security.interceptor.AuthorizationInterceptor;
import com.commerce.auth.application.usecase.TokenAuthenticationService;
import com.commerce.auth.application.result.TokenAuthenticationResult;
import com.commerce.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.config.WebConfig;
import com.commerce.payment.application.service.ReservePaymentService;
import com.commerce.payment.application.command.ReservePaymentCommand;
import com.commerce.payment.application.result.ReservePaymentResult;


@WebMvcTest(ReservePaymentController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class ReservePaymentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ReservePaymentService reservePaymentService;

	@MockitoBean
	private TokenAuthenticationService tokenAuthenticationService;

	@DisplayName("결제 예약 요청이 유효하면 결제 예약 응답을 반환한다")
	@Test
	void reserve_whenValidRequest_returnOk() throws Exception {
		// given
		stubForValidToken();

		ReservePaymentResult result = ReservePaymentResult.builder()
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
		given(reservePaymentService.reserve(any(ReservePaymentCommand.class)))
			.willReturn(result);

		String requestBody = """
			{
			  "orderId": 1,
			  "provider": "NAVERPAY"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/reserve")
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
	void reserve_whenProviderInvalid_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "orderId": 1,
			  "provider": "INVALID"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/reserve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT-400-1"))
			.andExpect(jsonPath("$.message").value("지원하지 않는 결제 수단입니다"))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@DisplayName("주문 ID가 없으면 요청 값 검증에 실패한다")
	@Test
	void reserve_whenOrderIdIsNull_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "provider": "NAVERPAY"
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/reserve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.data.orderId").value("주문 ID는 필수입니다"));
	}

	@DisplayName("결제 수단이 비어있으면 요청 값 검증에 실패한다")
	@Test
	void reserve_whenProviderIsBlank_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "orderId": 1,
			  "provider": " "
			}
			""";

		// when & then
		mockMvc.perform(post("/payments/reserve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.data.provider").value("결제 수단은 필수입니다"));
	}

	private void stubForValidToken() {
		given(tokenAuthenticationService.authenticateAccessToken("access-token"))
			.willReturn(TokenAuthenticationResult.of(1L, "ROLE_USER"));
	}
}
