package com.commerce.payment.presentation.http.naverpay;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
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

import com.commerce.common.security.Role;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.filter.TokenAuthenticationFilter;
import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.port.TokenValidator;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.payment.application.dto.ApprovalResult;
import com.commerce.payment.application.usecase.RequestApprovalUseCase;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

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

	private static final String REQUEST_BODY = """
		{
		  "merchantPayKey": "PAY-1",
		  "paymentId": "pg-payment-1"
		}
		""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RequestApprovalUseCase requestApprovalUseCase;

	@MockitoBean
	private TokenValidator tokenValidator;

	@DisplayName("결제창이 돌려보내는 주소는 밖에서 보이던 경로 그대로 응답한다")
	@Test
	void returnFromNaverPay_whenRedirected_respondsOk() throws Exception {
		mockMvc.perform(get("/payments/naverpay/return")
				.param("merchantPayKey", "PAY-1")
				.param("resultCode", "Success")
				.param("paymentId", "pg-payment-1"))
			.andExpect(status().isOk());
	}

	@DisplayName("승인 요청이 확정되면 결제사 결제 번호와 성공 상태가 응답된다")
	@Test
	void approve_whenConfirmed_returnsPgPaymentIdAndSuccess() throws Exception {
		stubForValidToken();
		given(requestApprovalUseCase.approve(1L, "PAY-1", "pg-payment-1"))
			.willReturn(ApprovalResult.succeeded("pg-payment-1"));

		mockMvc.perform(post("/payments/naverpay/approve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.pgPaymentId").value("pg-payment-1"))
			.andExpect(jsonPath("$.data.status").value("SUCCESS"));
	}

	@DisplayName("승인 결과를 모르면 확인 중이라는 값이 나간다")
	@Test
	void approve_whenResultUnknown_returnsPendingCode() throws Exception {
		stubForValidToken();
		given(requestApprovalUseCase.approve(1L, "PAY-1", "pg-payment-1"))
			.willThrow(new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING));

		mockMvc.perform(post("/payments/naverpay/approve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentErrorCode.PAYMENT_RESULT_PENDING.getCode()));
	}

	@DisplayName("인증이 없으면 승인 흐름까지 가지 않는다")
	@Test
	void approve_whenUnauthenticated_doesNotReachUseCase() throws Exception {
		mockMvc.perform(post("/payments/naverpay/approve")
				.contentType(MediaType.APPLICATION_JSON)
				.content(REQUEST_BODY))
			.andExpect(status().isUnauthorized());

		then(requestApprovalUseCase).should(never()).approve(anyLong(), anyString(), anyString());
	}

	@DisplayName("결제 키가 비면 승인 흐름까지 가지 않는다")
	@Test
	void approve_whenPaymentKeyBlank_doesNotReachUseCase() throws Exception {
		stubForValidToken();

		mockMvc.perform(post("/payments/naverpay/approve")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "merchantPayKey": "",
					  "paymentId": "pg-payment-1"
					}
					"""))
			.andExpect(status().isBadRequest());

		then(requestApprovalUseCase).should(never()).approve(anyLong(), anyString(), anyString());
	}

	private void stubForValidToken() {
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));
	}
}
