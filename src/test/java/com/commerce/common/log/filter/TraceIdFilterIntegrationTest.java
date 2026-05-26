package com.commerce.common.log.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.log.LogContext;
import com.commerce.security.filter.JwtAuthenticationFilter;

@WebMvcTest(
	controllers = TraceIdFilterIntegrationTest.TestController.class,
	excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
	excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({TraceIdFilterConfig.class, TraceIdFilterIntegrationTest.TestController.class})
class TraceIdFilterIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@RestController
	static class TestController {
		@GetMapping("/__test__/trace")
		ResponseEntity<Void> probe() {
			return ResponseEntity.ok().build();
		}
	}

	@DisplayName("GET 요청 응답에 X-Trace-Id 헤더가 포함된다")
	@Test
	void request_responseContainsTraceIdHeader() throws Exception {
		mockMvc.perform(get("/__test__/trace"))
			.andExpect(status().isOk())
			.andExpect(header().exists(LogContext.TRACE_ID_HEADER));
	}

	@DisplayName("두 번 호출하면 서로 다른 traceId가 발급된다")
	@Test
	void twoRequests_differentTraceIds() throws Exception {
		MvcResult result1 = mockMvc.perform(get("/__test__/trace")).andReturn();
		MvcResult result2 = mockMvc.perform(get("/__test__/trace")).andReturn();

		String traceId1 = result1.getResponse().getHeader(LogContext.TRACE_ID_HEADER);
		String traceId2 = result2.getResponse().getHeader(LogContext.TRACE_ID_HEADER);

		assertThat(traceId1).isNotNull();
		assertThat(traceId2).isNotNull();
		assertThat(traceId1).isNotEqualTo(traceId2);
	}

	@DisplayName("유효한 X-Trace-Id 요청 헤더가 있으면 응답 헤더에 동일 값이 돌아온다")
	@Test
	void validIncomingHeader_sameValueInResponse() throws Exception {
		mockMvc.perform(get("/__test__/trace")
				.header(LogContext.TRACE_ID_HEADER, "existing-trace-123"))
			.andExpect(status().isOk())
			.andExpect(header().string(LogContext.TRACE_ID_HEADER, "existing-trace-123"));
	}

	@DisplayName("부적합한 X-Trace-Id 요청 헤더는 차단하고 새 UUID를 발급한다")
	@Test
	void invalidIncomingHeader_newUuidInResponse() throws Exception {
		MvcResult result = mockMvc.perform(get("/__test__/trace")
				.header(LogContext.TRACE_ID_HEADER, "<script>alert(1)</script>"))
			.andReturn();

		String traceId = result.getResponse().getHeader(LogContext.TRACE_ID_HEADER);
		assertThat(traceId).isNotNull();
		assertThat(traceId).doesNotContain("<script>");
		assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}
}
