package com.commerce.product.presentation.http;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.commerce.security.filter.JwtAuthenticationFilter;
import com.commerce.security.interceptor.AuthorizationInterceptor;
import com.commerce.auth.application.TokenAuthenticationService;
import com.commerce.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.config.WebConfig;
import com.commerce.product.application.ProductQueryService;
import com.commerce.product.application.result.ProductDetailResult;
import com.commerce.product.application.result.ProductSummaryResult;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class ProductControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProductQueryService productQueryService;

	@MockitoBean
	private TokenAuthenticationService tokenAuthenticationService;

	@DisplayName("상품 목록 조회는 인증 없이 성공한다")
	@Test
	void getProducts_whenAnonymousRequest_returnOk() throws Exception {
		// given
		given(productQueryService.getProducts()).willReturn(List.of(
			ProductSummaryResult.builder()
				.productId(2L)
				.name("latest-product")
				.price(3000)
				.build(),
			ProductSummaryResult.builder()
				.productId(1L)
				.name("old-product")
				.price(1000)
				.build()
		));

		// when & then
		mockMvc.perform(get("/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data", Matchers.hasSize(2)))
			.andExpect(jsonPath("$.data[0].productId").value(2L))
			.andExpect(jsonPath("$.data[0].name").value("latest-product"))
			.andExpect(jsonPath("$.data[0].price").value(3000))
			.andExpect(jsonPath("$.data[0].stockQuantity").doesNotExist())
			.andExpect(jsonPath("$.data[1].productId").value(1L))
			.andExpect(jsonPath("$.data[1].name").value("old-product"))
			.andExpect(jsonPath("$.data[1].price").value(1000))
			.andExpect(jsonPath("$.data[1].stockQuantity").doesNotExist());
	}

	@DisplayName("상품 상세 조회는 재고 수량을 포함해 반환한다")
	@Test
	void getProduct_whenAnonymousRequest_returnOk() throws Exception {
		// given
		given(productQueryService.getProduct(2L)).willReturn(ProductDetailResult.builder()
			.productId(2L)
			.name("latest-product")
			.price(3000)
			.stockQuantity(7)
			.build());

		// when & then
		mockMvc.perform(get("/products/2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(2L))
			.andExpect(jsonPath("$.data.name").value("latest-product"))
			.andExpect(jsonPath("$.data.price").value(3000))
			.andExpect(jsonPath("$.data.stockQuantity").value(7));
	}

	@DisplayName("없는 상품 상세 조회는 404를 반환한다")
	@Test
	void getProduct_whenProductMissing_returnNotFound() throws Exception {
		// given
		given(productQueryService.getProduct(999L))
			.willThrow(new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		// when & then
		mockMvc.perform(get("/products/999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT-404"))
			.andExpect(jsonPath("$.message").value("상품을 찾을 수 없습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(productQueryService).should(never()).getProducts();
	}

	@DisplayName("상품 ID가 양수가 아니면 잘못된 요청을 반환한다")
	@Test
	void getProduct_whenProductIdIsNotPositive_returnBadRequest() throws Exception {
		// when & then
		mockMvc.perform(get("/products/0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(productQueryService).should(never()).getProduct(0L);
	}
}
