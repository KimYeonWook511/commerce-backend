package com.commerce.common.log;

import java.util.regex.Pattern;

public final class MdcKeys {
	public static final String TRACE_ID = "traceId";
	public static final String MEMBER_ID = "memberId";
	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	public static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private MdcKeys() {
	}
}
