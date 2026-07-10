package com.commerce.common.log;

import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class LogContext {

	private static final String TRACE_ID = "traceId";
	private static final String MEMBER_ID = "memberId";
	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private LogContext() {
	}

	public static void putTraceId(String traceId) {
		MDC.put(TRACE_ID, traceId);
	}

	public static String getTraceId() {
		return MDC.get(TRACE_ID);
	}

	public static void removeTraceId() {
		MDC.remove(TRACE_ID);
	}

	public static boolean isValidTraceId(String traceId) {
		return traceId != null && VALID_TRACE_ID.matcher(traceId).matches();
	}

	public static void putMemberId(long memberId) {
		MDC.put(MEMBER_ID, String.valueOf(memberId));
	}

	public static String getMemberId() {
		return MDC.get(MEMBER_ID);
	}

	public static void clear() {
		MDC.clear();
	}
}
