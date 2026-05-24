package com.commerce.common.log.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

public class TraceIdRecordInterceptor implements RecordInterceptor<Object, Object> {

	private static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final String TRACE_ID_MDC_KEY = "traceId";
	private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	@Override
	public ConsumerRecord<Object, Object> intercept(
		ConsumerRecord<Object, Object> record,
		Consumer<Object, Object> consumer
	) {
		String traceId = resolveTraceId(record);
		MDC.put(TRACE_ID_MDC_KEY, traceId);
		return record;
	}

	@Override
	public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
	}

	@Override
	public void failure(
		ConsumerRecord<Object, Object> record,
		Exception exception,
		Consumer<Object, Object> consumer
	) {
		// MDC 정리는 error handler 이후 호출되는 afterRecord()에서 수행한다.
		// 여기서 제거하면 DefaultErrorHandler와 DeadLetterPublishingRecoverer 실행 시 traceId가 누락된다.
	}

	@Override
	public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
		MDC.remove(TRACE_ID_MDC_KEY);
	}

	private String resolveTraceId(ConsumerRecord<Object, Object> record) {
		Header header = record.headers().lastHeader(TRACE_ID_HEADER);
		if (header != null && header.value() != null) {
			String value = new String(header.value(), StandardCharsets.UTF_8);
			if (VALID_TRACE_ID.matcher(value).matches()) {
				return value;
			}
		}
		return UUID.randomUUID().toString();
	}
}
