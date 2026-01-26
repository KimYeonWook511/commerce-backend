package com.commerce.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
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
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.service.OrderService;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.order.service.response.OrderCancelResponse;
import com.commerce.order.service.response.OrderCreateResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

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
	private OrderService orderService;

	@MockitoBean
	private JwtTokenValidator jwtTokenValidator;

	@DisplayName("멱등키가 있으면 주문 생성 응답을 반환한다")
	@Test
	void createOrder_whenIdempotencyKeyPresent_returnCreated() throws Exception {
		// given
		stubForValidToken();
		OrderCreateResponse response = OrderCreateResponse.builder()
			.orderId(10L)
			.totalPrice(2000)
			.status(OrderStatus.INIT)
			.build();

		given(orderService.createOrder(any(OrderCreateServiceRequest.class)))
			.willReturn(response);

		String requestBody = """
			{
			  "items": [
			    {
			      "productId": 10,
			      "quantity": 2
			    }
			  ]
			}
			""";

		// when & then
		mockMvc.perform(post("/orders")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "idempotency-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.orderId").value(10L))
			.andExpect(jsonPath("$.data.totalPrice").value(2000))
			.andExpect(jsonPath("$.data.status").value("INIT"));
	}

	@DisplayName("멱등키가 없으면 요청이 실패한다")
	@Test
	void createOrder_whenIdempotencyKeyMissing_returnBadRequest() throws Exception {
		// given
		stubForValidToken();
		String requestBody = """
			{
			  "items": [
			    {
			      "productId": 10,
			      "quantity": 2
			    }
			  ]
			}
			""";

		// when & then
		mockMvc.perform(post("/orders")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(orderService).should(never()).createOrder(any(OrderCreateServiceRequest.class));
	}

	@DisplayName("주문 취소 요청이 유효하면 상태를 반환한다")
	@Test
	void cancelOrder_whenValidRequest_returnOk() throws Exception {
		// given
		stubForValidToken();
		OrderCancelResponse response = OrderCancelResponse.builder()
			.orderId(10L)
			.status(OrderStatus.CANCELED)
			.build();

		given(orderService.cancelOrder(1L, 10L)).willReturn(response);

		// when & then
		mockMvc.perform(post("/orders/10/cancel")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.orderId").value(10L))
			.andExpect(jsonPath("$.data.status").value("CANCELED"));
	}

	private void stubForValidToken() {
		Claims claims = Jwts.claims().setSubject("1");
		claims.put("role", "ROLE_USER");
		given(jwtTokenValidator.validateAccessToken("access-token")).willReturn(claims);
	}
}
