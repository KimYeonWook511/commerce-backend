package com.commerce.product.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.commerce.security.filter.JwtAuthenticationFilter;
import com.commerce.security.interceptor.AuthorizationInterceptor;
import com.commerce.auth.application.TokenAuthenticationService;
import com.commerce.auth.application.result.TokenAuthenticationResult;
import com.commerce.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.config.WebConfig;
import com.commerce.product.application.AdminProductService;
import com.commerce.product.application.command.AdminProductCreateCommand;
import com.commerce.product.application.command.AdminProductUpdateCommand;
import com.commerce.product.application.result.AdminProductDeleteResult;
import com.commerce.product.application.result.AdminProductResult;
import com.commerce.product.domain.ProductStatus;


@WebMvcTest(AdminProductController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class AdminProductControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminProductService adminProductService;

	@MockitoBean
	private TokenAuthenticationService tokenAuthenticationService;

	@DisplayName("관리자는 상품을 등록할 수 있다")
	@Test
	void createProduct_whenAdminRequest_returnCreated() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminProductService.createProduct(any(AdminProductCreateCommand.class)))
			.willReturn(AdminProductResult.builder()
				.productId(1L)
				.name("product")
				.price(10000)
				.description("description")
				.imageUrl("https://example.com/product.png")
				.status(ProductStatus.ON_SALE)
				.build());

		String requestBody = """
			{
			  "name": "product",
			  "price": 10000,
			  "description": "description",
			  "imageUrl": "https://example.com/product.png",
			  "status": "ON_SALE"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(1L))
			.andExpect(jsonPath("$.data.name").value("product"))
			.andExpect(jsonPath("$.data.price").value(10000))
			.andExpect(jsonPath("$.data.description").value("description"))
			.andExpect(jsonPath("$.data.imageUrl").value("https://example.com/product.png"))
			.andExpect(jsonPath("$.data.status").value("ON_SALE"));
	}

	@DisplayName("관리자는 상품을 수정할 수 있다")
	@Test
	void updateProduct_whenAdminRequest_returnOk() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminProductService.updateProduct(any(AdminProductUpdateCommand.class)))
			.willReturn(AdminProductResult.builder()
				.productId(1L)
				.name("updated-product")
				.price(12000)
				.description("updated description")
				.imageUrl("https://example.com/updated.png")
				.status(ProductStatus.SOLD_OUT)
				.build());

		String requestBody = """
			{
			  "name": "updated-product",
			  "price": 12000,
			  "description": "updated description",
			  "imageUrl": "https://example.com/updated.png",
			  "status": "SOLD_OUT"
			}
			""";

		// when & then
		mockMvc.perform(patch("/admin/products/1")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(1L))
			.andExpect(jsonPath("$.data.name").value("updated-product"))
			.andExpect(jsonPath("$.data.price").value(12000))
			.andExpect(jsonPath("$.data.description").value("updated description"))
			.andExpect(jsonPath("$.data.imageUrl").value("https://example.com/updated.png"))
			.andExpect(jsonPath("$.data.status").value("SOLD_OUT"));
	}

	@DisplayName("관리자는 상품을 soft delete 할 수 있다")
	@Test
	void deleteProduct_whenAdminRequest_returnOk() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminProductService.deleteProduct(1L))
			.willReturn(AdminProductDeleteResult.of(1L));

		// when & then
		mockMvc.perform(delete("/admin/products/1")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(1L))
			.andExpect(jsonPath("$.data.deleted").value(true));
	}

	@DisplayName("관리자 권한이 없으면 상품 등록이 실패한다")
	@Test
	void createProduct_whenRoleIsUser_returnForbidden() throws Exception {
		// given
		stubForToken("ROLE_USER");
		String requestBody = """
			{
			  "name": "product",
			  "price": 10000,
			  "status": "ON_SALE"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH-403"))
			.andExpect(jsonPath("$.message").value("권한이 없습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminProductService).should(never()).createProduct(any(AdminProductCreateCommand.class));
	}

	@DisplayName("상품 등록 요청 값이 유효하지 않으면 검증 오류를 반환한다")
	@Test
	void createProduct_whenInvalidRequest_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "name": " ",
			  "price": 0
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data.name").value("상품명은 필수입니다"))
			.andExpect(jsonPath("$.data.price").value("가격은 양수여야 합니다"))
			.andExpect(jsonPath("$.data.status").value("판매 상태는 필수입니다"));

		then(adminProductService).should(never()).createProduct(any(AdminProductCreateCommand.class));
	}

	@DisplayName("상품 등록 요청의 판매 상태가 enum 값이 아니면 잘못된 요청을 반환한다")
	@Test
	void createProduct_whenStatusIsInvalidEnum_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "name": "product",
			  "price": 10000,
			  "status": "INVALID"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminProductService).should(never()).createProduct(any(AdminProductCreateCommand.class));
	}

	@DisplayName("상품 수정 경로 ID가 양수가 아니면 실패한다")
	@Test
	void updateProduct_whenProductIdIsNotPositive_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "name": "product",
			  "price": 10000,
			  "status": "ON_SALE"
			}
			""";

		// when & then
		mockMvc.perform(patch("/admin/products/0")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminProductService).should(never()).updateProduct(any(AdminProductUpdateCommand.class));
	}

	private void stubForToken(String role) {
		given(tokenAuthenticationService.authenticateAccessToken("access-token"))
			.willReturn(TokenAuthenticationResult.of(1L, role));
	}
}
