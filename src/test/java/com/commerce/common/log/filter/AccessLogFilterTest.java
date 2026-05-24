package com.commerce.common.log.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

class AccessLogFilterTest {

	private final AccessLogFilter filter = new AccessLogFilter();
	private ListAppender<ILoggingEvent> listAppender;
	private Logger accessLogLogger;

	@BeforeEach
	void setUp() {
		accessLogLogger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
		listAppender = new ListAppender<>();
		listAppender.start();
		accessLogLogger.addAppender(listAppender);
	}

	@AfterEach
	void tearDown() {
		accessLogLogger.detachAppender(listAppender);
		MDC.clear();
	}

	@DisplayName("정상 200 응답 시 요청 시작 INFO + 요청 종료 INFO 2건이 남는다")
	@Test
	void normalRequest_twoInfoLogs() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products/1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("요청 시작").contains("method=GET").contains("path=/products/1");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(1).getFormattedMessage()).contains("요청 종료").contains("status=200").contains("latency=");
	}

	@DisplayName("4xx 응답 시 요청 종료 메시지에 status=404가 포함된다")
	@Test
	void request_404Response_statusInLog() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/not-found");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(404);

		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(1).getFormattedMessage()).contains("status=404");
	}

	@DisplayName("5xx 응답 시 요청 종료 메시지에 status=500이 포함된다")
	@Test
	void request_500Response_statusInLog() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(500);

		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(1).getFormattedMessage()).contains("status=500");
	}

	@DisplayName("chain.doFilter에서 RuntimeException이 발생해도 요청 종료 로그가 남는다")
	@Test
	void exceptionInChain_endLogIsStillWritten() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
		MockHttpServletResponse response = new MockHttpServletResponse();

		FilterChain chain = mock(FilterChain.class);
		doThrow(new ServletException("테스트 예외")).when(chain).doFilter(any(), any());

		assertThatThrownBy(() -> filter.doFilter(request, response, chain))
			.isInstanceOf(ServletException.class);

		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getFormattedMessage()).contains("요청 시작");
		assertThat(logs.get(1).getFormattedMessage()).contains("요청 종료").contains("status=500");
	}

	@DisplayName("요청 종료 로그에 latency 값이 포함된다")
	@Test
	void endLog_containsLatency() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		String endMessage = listAppender.list.get(1).getFormattedMessage();
		assertThat(endMessage).contains("latency=");
		assertThat(endMessage).containsPattern("latency=\\d+ms");
	}

	@DisplayName("MEMBER_ID_ATTRIBUTE가 없는 요청은 요청 종료 로그의 MDC에 memberId가 없다")
	@Test
	void noAttribute_endLogHasNoMemberId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		ILoggingEvent endLog = listAppender.list.get(1);
		assertThat(endLog.getMDCPropertyMap().get(AccessLogFilter.MEMBER_ID_MDC_KEY)).isNull();
		assertThat(MDC.get(AccessLogFilter.MEMBER_ID_MDC_KEY)).isNull();
	}

	@DisplayName("MEMBER_ID_ATTRIBUTE가 set된 요청은 요청 종료 로그의 MDC에 memberId가 포함되고 이후 제거된다")
	@Test
	void withAttribute_endLogHasMemberIdAndRemovedAfter() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, 42L);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		ILoggingEvent endLog = listAppender.list.get(1);
		assertThat(endLog.getMDCPropertyMap().get(AccessLogFilter.MEMBER_ID_MDC_KEY)).isEqualTo("42");
		assertThat(MDC.get(AccessLogFilter.MEMBER_ID_MDC_KEY)).isNull();
	}

	@DisplayName("chain 예외 시에도 MEMBER_ID_ATTRIBUTE가 set된 경우 MDC.remove가 보장된다")
	@Test
	void chainException_withAttribute_mdcRemovedInFinally() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, 42L);
		MockHttpServletResponse response = new MockHttpServletResponse();

		FilterChain chain = mock(FilterChain.class);
		doThrow(new ServletException("체인 예외")).when(chain).doFilter(any(), any());

		assertThatThrownBy(() -> filter.doFilter(request, response, chain))
			.isInstanceOf(ServletException.class);

		assertThat(MDC.get(AccessLogFilter.MEMBER_ID_MDC_KEY)).isNull();
	}

	@DisplayName("MEMBER_ID_ATTRIBUTE가 없는 요청 종료 후 MDC에 memberId 잔류가 없다")
	@Test
	void noAttribute_noMdcResidueAfterRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(MDC.get(AccessLogFilter.MEMBER_ID_MDC_KEY)).isNull();
	}
}
