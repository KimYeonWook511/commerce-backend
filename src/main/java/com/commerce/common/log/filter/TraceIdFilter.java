package com.commerce.common.log.filter;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import com.commerce.common.log.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class TraceIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {

		String incoming = request.getHeader(MdcKeys.TRACE_ID_HEADER);
		String traceId = (incoming != null && MdcKeys.VALID_TRACE_ID.matcher(incoming).matches())
			? incoming
			: UUID.randomUUID().toString();

		MDC.put(MdcKeys.TRACE_ID, traceId);
		try {
			response.setHeader(MdcKeys.TRACE_ID_HEADER, traceId);
			filterChain.doFilter(request, response);
		} finally {
			// MDC.clear() 금지 — P3/P4에서 push될 다른 키(userId, orderId 등)를 같이 날리는 위험 차단
			MDC.remove(MdcKeys.TRACE_ID);
		}
	}
}
