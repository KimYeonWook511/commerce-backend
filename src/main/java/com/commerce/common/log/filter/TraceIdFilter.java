package com.commerce.common.log.filter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class TraceIdFilter extends OncePerRequestFilter {

	static final String TRACE_ID_HEADER = "X-Trace-Id";
	static final String TRACE_ID_MDC_KEY = "traceId";
	private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {

		String incoming = request.getHeader(TRACE_ID_HEADER);
		String traceId = (incoming != null && VALID_TRACE_ID.matcher(incoming).matches())
			? incoming
			: UUID.randomUUID().toString();

		MDC.put(TRACE_ID_MDC_KEY, traceId);
		try {
			response.setHeader(TRACE_ID_HEADER, traceId);
			filterChain.doFilter(request, response);
		} finally {
			// MDC.clear() 금지 — P3/P4에서 push될 다른 키(userId, orderId 등)를 같이 날리는 위험 차단
			MDC.remove(TRACE_ID_MDC_KEY);
		}
	}
}
