package com.commerce.order.presentation.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

import com.commerce.auth.application.usecase.TokenAuthenticationService;
import com.commerce.auth.application.result.TokenAuthenticationResult;
import com.commerce.common.config.WebConfig;
import com.commerce.order.application.service.OrderCancelService;
import com.commerce.order.application.service.OrderCreateService;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.result.OrderCancelResult;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.domain.OrderStatus;
import com.commerce.security.filter.JwtAuthenticationFilter;
import com.commerce.security.interceptor.AuthorizationInterceptor;
import com.commerce.security.resolver.AuthenticatedMemberIdArgumentResolver;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class OrderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderCreateService orderCreateService;

	@MockitoBean
	private OrderCancelService orderCancelService;

	@MockitoBean
	private TokenAuthenticationService tokenAuthenticationService;

	@DisplayName("유효한 주문 생성 요청이면 201을 반환한다")
	@Test
	void createOrder_whenValidRequest_returnCreated() throws Exception {
		stubForToken();
		given(orderCreateService.createOrder(any(OrderCreateCommand.class)))
			.willReturn(OrderCreateResult.builder()
				.orderId(1L)
				.totalPrice(10000)
				.status(OrderStatus.INIT)
				.build());

		mockMvc.perform(post("/orders")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "unique-key-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [{"productId": 1, "quantity": 2}]}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.orderId").value(1))
			.andExpect(jsonPath("$.data.totalPrice").value(10000))
			.andExpect(jsonPath("$.data.status").value("INIT"));
	}

	@DisplayName("Idempotency-Key 헤더가 없으면 400을 반환한다")
	@Test
	void createOrder_whenMissingIdempotencyKey_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [{"productId": 1, "quantity": 2}]}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	@DisplayName("items가 비어있으면 400을 반환한다")
	@Test
	void createOrder_whenEmptyItems_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "unique-key-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": []}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	@DisplayName("유효한 주문 취소 요청이면 200을 반환한다")
	@Test
	void cancelOrder_whenValidRequest_returnOk() throws Exception {
		stubForToken();
		given(orderCancelService.cancelOrder(anyLong(), anyLong()))
			.willReturn(OrderCancelResult.builder()
				.orderId(1L)
				.status(OrderStatus.CANCELED)
				.build());

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.orderId").value(1))
			.andExpect(jsonPath("$.data.status").value("CANCELED"));
	}

	private void stubForToken() {
		given(tokenAuthenticationService.authenticateAccessToken("access-token"))
			.willReturn(TokenAuthenticationResult.of(1L, "ROLE_USER"));
	}
}
