package com.commerce.common.log.filter;

import java.io.IOException;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import com.commerce.common.log.LogContext;
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

		String incoming = request.getHeader(LogContext.TRACE_ID_HEADER);
		String traceId = LogContext.isValidTraceId(incoming) ? incoming : UUID.randomUUID().toString();

		LogContext.putTraceId(traceId);
		try {
			response.setHeader(LogContext.TRACE_ID_HEADER, traceId);
			filterChain.doFilter(request, response);
		} finally {
			// 이 필터가 MDC를 만지는 최외곽이라 요청 끝에서 clear()로 스레드 스코프 전체를 정리한다.
			// 단 이 전제는 TraceIdFilter가 최외곽으로 유지될 때만 성립한다.
			LogContext.clear();
		}
	}
}
