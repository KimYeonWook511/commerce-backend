package com.commerce.order.presentation.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

import com.commerce.common.security.port.TokenValidator;
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.order.application.usecase.CancelOrderUseCase;
import com.commerce.order.application.usecase.CreateOrderUseCase;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.domain.OrderCancelLine;
import com.commerce.order.domain.OrderStatus;
import com.commerce.common.security.filter.TokenAuthenticationFilter;
import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	SecurityWebMvcConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	TokenAuthenticationFilter.class
})
class OrderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CreateOrderUseCase createOrderUseCase;

	@MockitoBean
	private CancelOrderUseCase cancelOrderUseCase;

	@MockitoBean
	private TokenValidator tokenValidator;

	@DisplayName("유효한 주문 생성 요청이면 201을 반환한다")
	@Test
	void createOrder_whenValidRequest_returnCreated() throws Exception {
		stubForToken();
		given(createOrderUseCase.createOrder(any(OrderCreateCommand.class)))
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

	@DisplayName("주문 상품 목록에 null 원소가 있으면 400으로 거절한다")
	@Test
	void createOrder_whenItemIsNull_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "create-key-null")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [null]}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));

		then(createOrderUseCase).should(never()).createOrder(any());
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

	@DisplayName("유효한 주문 취소 요청이면 200과 함께 이번 환불액·남은 취소 가능 금액을 돌려준다")
	@Test
	void cancelOrder_whenValidRequest_returnOk() throws Exception {
		stubForToken();
		given(cancelOrderUseCase.cancel(anyLong(), anyLong(), anyString(), any()))
			.willReturn(OrderCancelResult.builder()
				.orderId(1L)
				.status(OrderStatus.CANCELED)
				.refundStatus(OrderCancelRefundStatus.COMPLETED)
				.refundedAmount(10000)
				.remainingAmount(0)
				.build());

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "cancel-key-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.orderId").value(1))
			.andExpect(jsonPath("$.data.status").value("CANCELED"))
			.andExpect(jsonPath("$.data.refundStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.data.refundedAmount").value(10000))
			.andExpect(jsonPath("$.data.remainingAmount").value(0));
	}

	@DisplayName("주문 취소에 Idempotency-Key 헤더가 없으면 400을 반환한다")
	@Test
	void cancelOrder_whenMissingIdempotencyKey_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	@DisplayName("길이 상한을 넘는 멱등키는 잘라 담지 않고 400으로 거절한다")
	@Test
	void cancelOrder_whenIdempotencyKeyTooLong_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "k".repeat(65)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	@DisplayName("취소할 품목과 수량을 본문에 실으면 그대로 유스케이스에 전달된다")
	@Test
	void cancelOrder_whenItemsGiven_passesThemToUseCase() throws Exception {
		stubForToken();
		given(cancelOrderUseCase.cancel(anyLong(), anyLong(), anyString(), any()))
			.willReturn(OrderCancelResult.builder()
				.orderId(1L)
				.status(OrderStatus.PAID)
				.refundStatus(OrderCancelRefundStatus.COMPLETED)
				.refundedAmount(10000)
				.remainingAmount(40000)
				.build());

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "cancel-key-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [{"orderItemId": 11, "quantity": 2}]}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("PAID"))
			.andExpect(jsonPath("$.data.refundedAmount").value(10000));

		then(cancelOrderUseCase).should().cancel(1L, 1L, "cancel-key-2", List.of(new OrderCancelLine(11L, 2)));
	}

	@DisplayName("빈 품목 목록은 목록을 싣지 않은 것과 같게 전달된다")
	@Test
	void cancelOrder_whenItemsEmpty_passesEmptyLinesLikeOmittedBody() throws Exception {
		stubForToken();
		given(cancelOrderUseCase.cancel(anyLong(), anyLong(), anyString(), any()))
			.willReturn(OrderCancelResult.builder()
				.orderId(1L)
				.status(OrderStatus.CANCELED)
				.refundStatus(OrderCancelRefundStatus.COMPLETED)
				.refundedAmount(40000)
				.remainingAmount(0)
				.build());

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "cancel-key-5")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": []}
					"""))
			.andExpect(status().isOk());

		then(cancelOrderUseCase).should().cancel(1L, 1L, "cancel-key-5", List.of());
	}

	@DisplayName("같은 주문 상품이 두 줄로 오면 합치지 않고 400으로 거절한다")
	@Test
	void cancelOrder_whenSameOrderItemAppearsTwice_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "cancel-key-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [{"orderItemId": 11, "quantity": 1}, {"orderItemId": 11, "quantity": 2}]}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));

		then(cancelOrderUseCase).should(never()).cancel(anyLong(), anyLong(), anyString(), any());
	}

	@DisplayName("취소 수량이 1 미만이면 400으로 거절한다")
	@Test
	void cancelOrder_whenQuantityNotPositive_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "cancel-key-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [{"orderItemId": 11, "quantity": 0}]}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));

		then(cancelOrderUseCase).should(never()).cancel(anyLong(), anyLong(), anyString(), any());
	}

	@DisplayName("취소 품목 목록에 null 원소가 있으면 400으로 거절한다")
	@Test
	void cancelOrder_whenItemIsNull_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.header("Idempotency-Key", "cancel-key-null")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [null]}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));

		then(cancelOrderUseCase).should(never()).cancel(anyLong(), anyLong(), anyString(), any());
	}

	@DisplayName("품목 목록을 실어도 Idempotency-Key 헤더가 없으면 400을 반환한다")
	@Test
	void cancelOrder_whenItemsGivenWithoutIdempotencyKey_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/orders/1/cancel")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"items": [{"orderItemId": 11, "quantity": 1}]}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	private void stubForToken() {
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));
	}
}
