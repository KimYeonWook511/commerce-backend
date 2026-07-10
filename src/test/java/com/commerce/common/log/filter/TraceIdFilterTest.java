package com.commerce.common.log.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.commerce.common.log.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	@AfterEach
	void cleanup() {
		MDC.clear();
	}

	@DisplayName("incoming X-Trace-Id 헤더가 없으면 새 UUID를 발급하고 응답 헤더에 set한다")
	@Test
	void noIncomingHeader_generatesNewUuid() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		String traceId = response.getHeader(LogContext.TRACE_ID_HEADER);
		assertThat(traceId).isNotNull();
		assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	@DisplayName("유효한 incoming X-Trace-Id 헤더가 있으면 동일 값을 응답 헤더에 set한다")
	@Test
	void validIncomingHeader_reusesValue() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(LogContext.TRACE_ID_HEADER, "existing-trace-123");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getHeader(LogContext.TRACE_ID_HEADER)).isEqualTo("existing-trace-123");
	}

	@DisplayName("65자 초과 incoming 헤더는 유효하지 않으므로 새 UUID를 발급한다")
	@Test
	void tooLongIncomingHeader_generatesNewUuid() throws Exception {
		String longId = "a".repeat(65);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(LogContext.TRACE_ID_HEADER, longId);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		String traceId = response.getHeader(LogContext.TRACE_ID_HEADER);
		assertThat(traceId).isNotEqualTo(longId);
		assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	@DisplayName("특수문자가 포함된 incoming 헤더는 유효하지 않으므로 새 UUID를 발급한다")
	@Test
	void specialCharIncomingHeader_generatesNewUuid() throws Exception {
		String xssId = "<script>alert(1)</script>";
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(LogContext.TRACE_ID_HEADER, xssId);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		String traceId = response.getHeader(LogContext.TRACE_ID_HEADER);
		assertThat(traceId).isNotEqualTo(xssId);
		assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	@DisplayName("chain.doFilter 실행 중 MDC에 traceId가 set되어 있다")
	@Test
	void duringChainExecution_mdcContainsTraceId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		AtomicReference<String> mdcDuringChain = new AtomicReference<>();
		FilterChain chain = mock(FilterChain.class);
		doAnswer(invocation -> {
			mdcDuringChain.set(LogContext.getTraceId());
			return null;
		}).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		String responseTraceId = response.getHeader(LogContext.TRACE_ID_HEADER);
		assertThat(mdcDuringChain.get()).isNotNull();
		assertThat(mdcDuringChain.get()).isEqualTo(responseTraceId);
	}

	@DisplayName("doFilter 완료 후 MDC에서 traceId가 제거된다")
	@Test
	void afterDoFilter_mdcTraceIdIsNull() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(LogContext.getTraceId()).isNull();
	}

	@DisplayName("chain.doFilter에서 예외가 발생해도 MDC에서 traceId가 제거된다")
	@Test
	void exceptionInChain_mdcTraceIdIsStillRemoved() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		FilterChain chain = mock(FilterChain.class);
		doThrow(new ServletException("테스트 예외")).when(chain).doFilter(any(), any());

		assertThatThrownBy(() -> filter.doFilter(request, response, chain))
			.isInstanceOf(ServletException.class);

		assertThat(LogContext.getTraceId()).isNull();
	}

	@DisplayName("doFilter 완료 후 clear()가 traceId뿐 아니라 memberId 등 다른 MDC 키도 함께 지운다")
	@Test
	void afterDoFilter_clearRemovesMemberIdToo() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		LogContext.putMemberId(42L);

		filter.doFilter(request, response, chain);

		assertThat(LogContext.getMemberId()).isNull();
		assertThat(LogContext.getTraceId()).isNull();
	}
}
