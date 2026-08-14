package com.commerce.payment.presentation.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
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

import com.commerce.common.security.Role;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.filter.TokenAuthenticationFilter;
import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.port.TokenValidator;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.payment.application.dto.StartPaymentCommand;
import com.commerce.payment.application.dto.StartPaymentResult;
import com.commerce.payment.application.usecase.StartPaymentUseCase;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	SecurityWebMvcConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	TokenAuthenticationFilter.class
})
class PaymentControllerTest {

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private static final String REQUEST_BODY = """
		{
		  "orderId": 1,
		  "provider": "NAVERPAY"
		}
		""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StartPaymentUseCase startPaymentUseCase;

	@MockitoBean
	private TokenValidator tokenValidator;

	@DisplayName("결제 시작 요청이 유효하면 결제창을 여는 값이 그대로 응답된다")
	@Test
	void startPayment_whenValidRequest_returnsCheckoutValues() throws Exception {
		stubForValidToken();
		given(startPaymentUseCase.start(any(StartPaymentCommand.class))).willReturn(
			new StartPaymentResult("clientId", "chainId", "PAY-1", "상품명", 1, 1000, 1000, 0, "https://return-url"));

		mockMvc.perform(post("/payments")
				.header("Authorization", "Bearer access-token")
				.header(IDEMPOTENCY_KEY_HEADER, "idem-key-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
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
			.andExpect(jsonPath("$.data.returnUrl").value("https://return-url"))
			// 프론트가 그대로 결제사 SDK 에 넘기므로 항목이 하나라도 늘면 안 된다.
			.andExpect(jsonPath("$.data.length()").value(9));
	}

	@DisplayName("멱등키 헤더가 없으면 요청 형식 검증으로 거절된다")
	@Test
	void startPayment_whenIdempotencyKeyMissing_returnsBadRequest() throws Exception {
		stubForValidToken();

		mockMvc.perform(post("/payments")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));

		then(startPaymentUseCase).should(never()).start(any());
	}

	@DisplayName("길이 상한을 넘는 멱등키는 잘라 담지 않고 요청 형식 검증으로 거절된다")
	@Test
	void startPayment_whenIdempotencyKeyTooLong_returnsBadRequest() throws Exception {
		stubForValidToken();

		mockMvc.perform(post("/payments")
				.header("Authorization", "Bearer access-token")
				.header(IDEMPOTENCY_KEY_HEADER, "a".repeat(65))
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));

		then(startPaymentUseCase).should(never()).start(any());
	}

	@DisplayName("길이 상한과 같은 멱등키는 통과한다")
	@Test
	void startPayment_whenIdempotencyKeyAtMaxLength_passes() throws Exception {
		stubForValidToken();
		given(startPaymentUseCase.start(any(StartPaymentCommand.class))).willReturn(
			new StartPaymentResult("clientId", "chainId", "PAY-1", "상품명", 1, 1000, 1000, 0, "https://return-url"));

		mockMvc.perform(post("/payments")
				.header("Authorization", "Bearer access-token")
				.header(IDEMPOTENCY_KEY_HEADER, "a".repeat(64))
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
			.andExpect(status().isOk());
	}

	@DisplayName("지원하지 않는 결제 수단이면 요청이 실패한다")
	@Test
	void startPayment_whenProviderNotSupported_returnsBadRequest() throws Exception {
		stubForValidToken();
		String requestBody = """
			{
			  "orderId": 1,
			  "provider": "INVALID"
			}
			""";

		mockMvc.perform(post("/payments")
				.header("Authorization", "Bearer access-token")
				.header(IDEMPOTENCY_KEY_HEADER, "idem-key-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT-400-19"));
	}

	@DisplayName("주문 ID가 없으면 요청 값 검증에 실패한다")
	@Test
	void startPayment_whenOrderIdIsNull_returnsBadRequest() throws Exception {
		stubForValidToken();
		String requestBody = """
			{
			  "provider": "NAVERPAY"
			}
			""";

		mockMvc.perform(post("/payments")
				.header("Authorization", "Bearer access-token")
				.header(IDEMPOTENCY_KEY_HEADER, "idem-key-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.data.orderId").value("주문 ID는 필수입니다"));
	}

	private void stubForValidToken() {
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));
	}
}
