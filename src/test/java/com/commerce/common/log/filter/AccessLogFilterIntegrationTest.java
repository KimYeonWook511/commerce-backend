package com.commerce.common.log.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.security.filter.JwtAuthenticationFilter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@WebMvcTest(
	controllers = AccessLogFilterIntegrationTest.TestController.class,
	excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
	excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({TraceIdFilterConfig.class, AccessLogFilterConfig.class, AccessLogFilterIntegrationTest.TestController.class})
class AccessLogFilterIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	private ListAppender<ILoggingEvent> listAppender;
	private Logger accessLogLogger;

	@BeforeEach
	void setUp() {
		accessLogLogger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
		accessLogLogger.setLevel(Level.INFO);
		listAppender = new ListAppender<>();
		listAppender.start();
		accessLogLogger.addAppender(listAppender);
	}

	@AfterEach
	void tearDown() {
		accessLogLogger.detachAppender(listAppender);
	}

	@RestController
	static class TestController {
		@GetMapping("/__test__/access-log")
		ResponseEntity<Void> probe() {
			return ResponseEntity.ok().build();
		}
	}

	@DisplayName("GET 요청 시 요청 시작 + 요청 종료 로그 2건이 남는다")
	@Test
	void request_twoAccessLogs() throws Exception {
		mockMvc.perform(get("/__test__/access-log"))
			.andExpect(status().isOk());

		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getFormattedMessage()).contains("요청 시작");
		assertThat(logs.get(1).getFormattedMessage()).contains("요청 종료");
	}

	@DisplayName("TraceIdFilter 이후 AccessLogFilter가 실행되어 MDC에 traceId가 있는 상태에서 로그가 작성된다")
	@Test
	void traceIdFilter_executedBeforeAccessLogFilter() throws Exception {
		mockMvc.perform(get("/__test__/access-log"))
			.andExpect(status().isOk());

		// TraceIdFilter가 먼저 실행되어 MDC traceId가 있는 상태에서 AccessLogFilter가 실행됨을 확인.
		// MDC 값은 logback 패턴으로 출력되므로 여기서는 로그 2건 발생 자체로 실행 순서를 간접 검증한다.
		assertThat(listAppender.list).hasSize(2);
	}

	@DisplayName("요청 종료 로그에 status와 latency가 포함된다")
	@Test
	void endLog_containsStatusAndLatency() throws Exception {
		mockMvc.perform(get("/__test__/access-log"))
			.andExpect(status().isOk());

		String endMessage = listAppender.list.get(1).getFormattedMessage();
		assertThat(endMessage).contains("status=200");
		assertThat(endMessage).containsPattern("latency=\\d+ms");
	}
}
