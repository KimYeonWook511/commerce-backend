package com.commerce.stock.presentation.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.commerce.common.security.port.TokenAuthenticator;
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.application.service.AdminDecreaseStockService;
import com.commerce.stock.application.service.AdminGetStockHistoryService;
import com.commerce.stock.application.service.AdminIncreaseStockService;
import com.commerce.stock.application.service.AdminInitializeStockService;
import com.commerce.stock.application.dto.AdminStockAdjustCommand;
import com.commerce.stock.application.dto.AdminStockCreateCommand;
import com.commerce.stock.application.dto.AdminStockResult;
import com.commerce.stock.application.dto.StockHistoryResult;


@WebMvcTest(AdminStockController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	SecurityWebMvcConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	TokenAuthenticationFilter.class
})
class AdminStockControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminInitializeStockService adminInitializeStockService;

	@MockitoBean
	private AdminIncreaseStockService adminIncreaseStockService;

	@MockitoBean
	private AdminDecreaseStockService adminDecreaseStockService;

	@MockitoBean
	private AdminGetStockHistoryService adminGetStockHistoryService;

	@MockitoBean
	private TokenAuthenticator tokenAuthenticator;

	@DisplayName("관리자는 초기 재고를 생성할 수 있다")
	@Test
	void createInitialStock_whenAdminRequest_returnCreated() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminInitializeStockService.createInitialStock(any(AdminStockCreateCommand.class)))
			.willReturn(AdminStockResult.builder()
				.productId(1L)
				.stockId(100L)
				.quantity(10)
				.build());

		String requestBody = """
			{
			  "quantity": 10,
			  "reason": "INBOUND"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(1L))
			.andExpect(jsonPath("$.data.stockId").value(100L))
			.andExpect(jsonPath("$.data.quantity").value(10));

		ArgumentCaptor<AdminStockCreateCommand> commandCaptor =
			ArgumentCaptor.forClass(AdminStockCreateCommand.class);
		then(adminInitializeStockService).should().createInitialStock(commandCaptor.capture());
		AdminStockCreateCommand command = commandCaptor.getValue();
		assertThat(command.getProductId()).isEqualTo(1L);
		assertThat(command.getQuantity()).isEqualTo(10);
		assertThat(command.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(command.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("관리자 권한이 없으면 초기 재고 생성이 실패한다")
	@Test
	void createInitialStock_whenRoleIsUser_returnForbidden() throws Exception {
		// given
		stubForToken("ROLE_USER");
		String requestBody = """
			{
			  "quantity": 10,
			  "reason": "INBOUND"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH-403"))
			.andExpect(jsonPath("$.message").value("권한이 없습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminInitializeStockService).should(never()).createInitialStock(any(AdminStockCreateCommand.class));
	}

	@DisplayName("초기 재고 생성 경로 ID가 양수가 아니면 실패한다")
	@Test
	void createInitialStock_whenProductIdIsNotPositive_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "quantity": 10,
			  "reason": "INBOUND"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/0/stock")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminInitializeStockService).should(never()).createInitialStock(any(AdminStockCreateCommand.class));
	}

	@DisplayName("초기 재고 생성 요청 값이 유효하지 않으면 검증 오류를 반환한다")
	@Test
	void createInitialStock_whenInvalidRequest_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "quantity": -1,
			  "reason": "INVALID"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data.quantity").value("재고 수량은 0 이상이어야 합니다"))
			.andExpect(jsonPath("$.data.reason").value("재고 변경 사유가 올바르지 않습니다"));

		then(adminInitializeStockService).should(never()).createInitialStock(any(AdminStockCreateCommand.class));
	}

	@DisplayName("초기 재고 생성 요청 필수 값이 없으면 검증 오류를 반환한다")
	@Test
	void createInitialStock_whenRequiredFieldsMissing_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data.quantity").value("재고 수량은 필수입니다"))
			.andExpect(jsonPath("$.data.reason").value("재고 변경 사유는 필수입니다"));

		then(adminInitializeStockService).should(never()).createInitialStock(any(AdminStockCreateCommand.class));
	}

	@DisplayName("관리자는 재고를 증가시킬 수 있다")
	@Test
	void increaseStock_whenAdminRequest_returnOk() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminIncreaseStockService.increaseByAdmin(any(AdminStockAdjustCommand.class)))
			.willReturn(AdminStockResult.builder()
				.productId(1L)
				.stockId(100L)
				.quantity(15)
				.build());

		String requestBody = """
			{
			  "quantity": 5,
			  "reason": "INBOUND"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock/increase")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(1L))
			.andExpect(jsonPath("$.data.stockId").value(100L))
			.andExpect(jsonPath("$.data.quantity").value(15));

		ArgumentCaptor<AdminStockAdjustCommand> commandCaptor =
			ArgumentCaptor.forClass(AdminStockAdjustCommand.class);
		then(adminIncreaseStockService).should().increaseByAdmin(commandCaptor.capture());
		AdminStockAdjustCommand command = commandCaptor.getValue();
		assertThat(command.getProductId()).isEqualTo(1L);
		assertThat(command.getQuantity()).isEqualTo(5);
		assertThat(command.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(command.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("관리자는 재고를 감소시킬 수 있다")
	@Test
	void decreaseStock_whenAdminRequest_returnOk() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminDecreaseStockService.decreaseByAdmin(any(AdminStockAdjustCommand.class)))
			.willReturn(AdminStockResult.builder()
				.productId(1L)
				.stockId(100L)
				.quantity(12)
				.build());

		String requestBody = """
			{
			  "quantity": 3,
			  "reason": "DISPOSAL"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock/decrease")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data.productId").value(1L))
			.andExpect(jsonPath("$.data.stockId").value(100L))
			.andExpect(jsonPath("$.data.quantity").value(12));

		ArgumentCaptor<AdminStockAdjustCommand> commandCaptor =
			ArgumentCaptor.forClass(AdminStockAdjustCommand.class);
		then(adminDecreaseStockService).should().decreaseByAdmin(commandCaptor.capture());
		AdminStockAdjustCommand command = commandCaptor.getValue();
		assertThat(command.getProductId()).isEqualTo(1L);
		assertThat(command.getQuantity()).isEqualTo(3);
		assertThat(command.getReason()).isEqualTo(StockAdjustmentReason.DISPOSAL);
		assertThat(command.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("관리자 권한이 없으면 재고 증가가 실패한다")
	@Test
	void increaseStock_whenRoleIsUser_returnForbidden() throws Exception {
		// given
		stubForToken("ROLE_USER");
		String requestBody = """
			{
			  "quantity": 5,
			  "reason": "INBOUND"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock/increase")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH-403"))
			.andExpect(jsonPath("$.message").value("권한이 없습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminIncreaseStockService).should(never()).increaseByAdmin(any(AdminStockAdjustCommand.class));
	}

	@DisplayName("재고 조정 경로 ID가 양수가 아니면 실패한다")
	@Test
	void increaseStock_whenProductIdIsNotPositive_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "quantity": 5,
			  "reason": "INBOUND"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/0/stock/increase")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminIncreaseStockService).should(never()).increaseByAdmin(any(AdminStockAdjustCommand.class));
	}

	@DisplayName("재고 조정 요청 값이 유효하지 않으면 검증 오류를 반환한다")
	@Test
	void decreaseStock_whenInvalidRequest_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			  "quantity": 0,
			  "reason": "INVALID"
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock/decrease")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data.quantity").value("재고 수량은 0보다 커야 합니다"))
			.andExpect(jsonPath("$.data.reason").value("재고 변경 사유가 올바르지 않습니다"));

		then(adminDecreaseStockService).should(never()).decreaseByAdmin(any(AdminStockAdjustCommand.class));
	}

	@DisplayName("재고 조정 요청 필수 값이 없으면 검증 오류를 반환한다")
	@Test
	void decreaseStock_whenRequiredFieldsMissing_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		String requestBody = """
			{
			}
			""";

		// when & then
		mockMvc.perform(post("/admin/products/1/stock/decrease")
				.header("Authorization", "Bearer access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data.quantity").value("재고 수량은 필수입니다"))
			.andExpect(jsonPath("$.data.reason").value("재고 변경 사유는 필수입니다"));

		then(adminDecreaseStockService).should(never()).decreaseByAdmin(any(AdminStockAdjustCommand.class));
	}

	@DisplayName("관리자는 상품별 재고 이력을 조회할 수 있다")
	@Test
	void getStockHistories_whenAdminRequest_returnOk() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");
		given(adminGetStockHistoryService.getHistoriesByProductId(1L))
			.willReturn(List.of(
				StockHistoryResult.builder()
					.historyId(2L)
					.productId(1L)
					.stockId(100L)
					.quantityChange(-3)
					.reason(StockAdjustmentReason.DISPOSAL)
					.adminMemberId(10L)
					.createdAt(LocalDateTime.of(2026, 4, 30, 12, 30))
					.build(),
				StockHistoryResult.builder()
					.historyId(1L)
					.productId(1L)
					.stockId(100L)
					.quantityChange(10)
					.reason(StockAdjustmentReason.INBOUND)
					.adminMemberId(10L)
					.createdAt(LocalDateTime.of(2026, 4, 30, 12, 0))
					.build()
			));

		// when & then
		mockMvc.perform(get("/admin/products/1/stock/histories")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("OK"))
			.andExpect(jsonPath("$.data[0].historyId").value(2L))
			.andExpect(jsonPath("$.data[0].productId").value(1L))
			.andExpect(jsonPath("$.data[0].stockId").value(100L))
			.andExpect(jsonPath("$.data[0].quantityChange").value(-3))
			.andExpect(jsonPath("$.data[0].reason").value("DISPOSAL"))
			.andExpect(jsonPath("$.data[0].adminMemberId").value(10L))
			.andExpect(jsonPath("$.data[0].createdAt").value("2026-04-30T12:30:00"))
			.andExpect(jsonPath("$.data[1].historyId").value(1L));

		then(adminGetStockHistoryService).should().getHistoriesByProductId(1L);
	}

	@DisplayName("관리자 권한이 없으면 재고 이력 조회가 실패한다")
	@Test
	void getStockHistories_whenRoleIsUser_returnForbidden() throws Exception {
		// given
		stubForToken("ROLE_USER");

		// when & then
		mockMvc.perform(get("/admin/products/1/stock/histories")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH-403"))
			.andExpect(jsonPath("$.message").value("권한이 없습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminGetStockHistoryService).should(never()).getHistoriesByProductId(any());
	}

	@DisplayName("재고 이력 조회 경로 ID가 양수가 아니면 실패한다")
	@Test
	void getStockHistories_whenProductIdIsNotPositive_returnBadRequest() throws Exception {
		// given
		stubForToken("ROLE_ADMIN");

		// when & then
		mockMvc.perform(get("/admin/products/0/stock/histories")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		then(adminGetStockHistoryService).should(never()).getHistoriesByProductId(any());
	}

	private void stubForToken(String role) {
		given(tokenAuthenticator.authenticate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.valueOf(role)));
	}
}
