package com.commerce.cart.presentation.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.commerce.cart.application.usecase.AddCartItemUseCase;
import com.commerce.cart.application.service.GetMyCartService;
import com.commerce.cart.application.service.RemoveCartItemService;
import com.commerce.cart.application.usecase.UpdateCartItemQuantityUseCase;
import com.commerce.cart.application.dto.CartItemSummaryResult;
import com.commerce.cart.domain.exception.CartErrorCode;
import com.commerce.cart.domain.exception.CartException;
import com.commerce.cart.application.dto.CartItemResult;
import com.commerce.cart.application.dto.CartResult;
import com.commerce.cart.presentation.http.request.CartItemAddRequest;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.common.security.filter.TokenAuthenticationFilter;
import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;

@WebMvcTest(CartController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	SecurityWebMvcConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	TokenAuthenticationFilter.class
})
class CartControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AddCartItemUseCase addCartItemUseCase;

	@MockitoBean
	private GetMyCartService getMyCartService;

	@MockitoBean
	private UpdateCartItemQuantityUseCase updateCartItemQuantityUseCase;

	@MockitoBean
	private RemoveCartItemService removeCartItemService;

	@MockitoBean
	private TokenValidator tokenValidator;

	@DisplayName("유효한 장바구니 담기 요청이면 201을 반환한다")
	@Test
	void addCartItem_whenValidRequest_returnCreated() throws Exception {
		stubForToken();
		given(addCartItemUseCase.add(anyLong(), any(CartItemAddRequest.class)))
			.willReturn(CartItemSummaryResult.builder()
				.productId(123L)
				.quantity(5)
				.build());

		mockMvc.perform(post("/cart/items")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"productId": 123, "quantity": 2}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.productId").value(123))
			.andExpect(jsonPath("$.data.quantity").value(5));
	}

	@DisplayName("quantity가 0이면 400을 반환한다")
	@Test
	void addCartItem_whenQuantityZero_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/cart/items")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"productId": 1, "quantity": 0}
					"""))
			.andExpect(status().isBadRequest());
	}

	@DisplayName("productId가 음수이면 400을 반환한다")
	@Test
	void addCartItem_whenProductIdNegative_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/cart/items")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"productId": -1, "quantity": 2}
					"""))
			.andExpect(status().isBadRequest());
	}

	@DisplayName("productId가 null이면 400을 반환한다")
	@Test
	void addCartItem_whenProductIdNull_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(post("/cart/items")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quantity": 2}
					"""))
			.andExpect(status().isBadRequest());
	}

	@DisplayName("상품이 존재하지 않으면 CART_ITEM_PRODUCT_NOT_FOUND 4xx를 반환한다 (결정 6-5)")
	@Test
	void addCartItem_whenProductNotFound_returnNotFound() throws Exception {
		stubForToken();
		willThrow(new CartException(CartErrorCode.CART_ITEM_PRODUCT_NOT_FOUND))
			.given(addCartItemUseCase).add(anyLong(), argThat(request -> request != null && request.getProductId() == 999L));

		mockMvc.perform(post("/cart/items")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"productId": 999, "quantity": 2}
					"""))
			.andExpect(status().isNotFound());
	}

	@DisplayName("상품이 STOPPED면 CART_ITEM_PRODUCT_UNAVAILABLE 409를 반환한다 (결정 6-5)")
	@Test
	void addCartItem_whenProductUnavailable_returnConflict() throws Exception {
		stubForToken();
		willThrow(new CartException(CartErrorCode.CART_ITEM_PRODUCT_UNAVAILABLE))
			.given(addCartItemUseCase).add(anyLong(), argThat(request -> request != null && request.getProductId() == 888L));

		mockMvc.perform(post("/cart/items")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"productId": 888, "quantity": 2}
					"""))
			.andExpect(status().isConflict());
	}

	@DisplayName("내 장바구니 조회는 200을 반환한다")
	@Test
	void getMyCart_whenAuthenticated_returnOk() throws Exception {
		stubForToken();
		CartItemResult itemView = CartItemResult.builder()
			.productId(10L)
			.name("product-1")
			.price(1000)
			.imageUrl("https://img/1.png")
			.quantity(2)
			.lineAmount(2000)
			.unavailable(false)
			.build();
		given(getMyCartService.get(anyLong()))
			.willReturn(CartResult.builder()
				.items(List.of(itemView))
				.totalAmount(2000)
				.build());

		mockMvc.perform(get("/cart")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.items[0].productId").value(10))
			.andExpect(jsonPath("$.data.items[0].name").value("product-1"))
			.andExpect(jsonPath("$.data.items[0].price").value(1000))
			.andExpect(jsonPath("$.data.items[0].quantity").value(2))
			.andExpect(jsonPath("$.data.items[0].lineAmount").value(2000))
			.andExpect(jsonPath("$.data.items[0].unavailable").value(false))
			.andExpect(jsonPath("$.data.totalAmount").value(2000));
	}

	@DisplayName("유효한 수량 변경 요청이면 200을 반환한다")
	@Test
	void updateCartItem_whenValidRequest_returnOk() throws Exception {
		stubForToken();
		given(updateCartItemQuantityUseCase.update(anyLong(), eq(123L), any(CartItemUpdateRequest.class)))
			.willReturn(CartItemSummaryResult.builder()
				.productId(123L)
				.quantity(5)
				.build());

		mockMvc.perform(patch("/cart/items/{productId}", 123L)
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quantity": 5}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.productId").value(123))
			.andExpect(jsonPath("$.data.quantity").value(5));
	}

	@DisplayName("수량 변경 quantity가 0이면 400을 반환한다")
	@Test
	void updateCartItem_whenQuantityZero_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(patch("/cart/items/{productId}", 123L)
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quantity": 0}
					"""))
			.andExpect(status().isBadRequest());
	}

	@DisplayName("수량 변경 quantity가 100이면 400을 반환한다")
	@Test
	void updateCartItem_whenQuantityOverMax_returnBadRequest() throws Exception {
		stubForToken();

		mockMvc.perform(patch("/cart/items/{productId}", 123L)
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quantity": 100}
					"""))
			.andExpect(status().isBadRequest());
	}

	@DisplayName("장바구니 항목 삭제는 200을 반환한다")
	@Test
	void removeCartItem_whenAuthenticated_returnOk() throws Exception {
		stubForToken();
		willDoNothing().given(removeCartItemService).remove(anyLong(), eq(123L));

		mockMvc.perform(delete("/cart/items/{productId}", 123L)
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"));

		then(removeCartItemService).should().remove(anyLong(), eq(123L));
	}

	@DisplayName("존재하지 않는 항목 삭제 요청은 CART_ITEM_NOT_FOUND 4xx를 반환한다 (결정 6-4)")
	@Test
	void removeCartItem_whenNotExists_returnNotFound() throws Exception {
		stubForToken();
		willThrow(new CartException(CartErrorCode.CART_ITEM_NOT_FOUND))
			.given(removeCartItemService).remove(anyLong(), eq(999L));

		mockMvc.perform(delete("/cart/items/{productId}", 999L)
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isNotFound());

		then(removeCartItemService).should().remove(anyLong(), eq(999L));
	}

	private void stubForToken() {
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));
	}
}
