package com.commerce.common.log.filter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

// path 제외 목록 미적용 (YAGNI). actuator 도입 또는 noisy 발견 시 추가 검토
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {

		long startNanos = System.nanoTime();
		log.info("요청 시작 method={} path={}", request.getMethod(), request.getRequestURI());

		int status = 500;
		try {
			filterChain.doFilter(request, response);
			status = response.getStatus();
		} finally {
			long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
			log.info("요청 종료 status={} latency={}ms", status, durationMs);
		}
	}
}
