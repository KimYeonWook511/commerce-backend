package com.commerce.common.log.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

public class TraceIdKafkaProducerInterceptor implements ProducerInterceptor<Object, Object> {

	private static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final String TRACE_ID_MDC_KEY = "traceId";
	private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	@Override
	public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
		Headers headers = record.headers();
		if (headers.lastHeader(TRACE_ID_HEADER) != null) {
			return record;
		}
		String traceId = resolveTraceId();
		headers.add(TRACE_ID_HEADER, traceId.getBytes(StandardCharsets.UTF_8));
		return record;
	}

	private String resolveTraceId() {
		String mdc = MDC.get(TRACE_ID_MDC_KEY);
		if (mdc != null && VALID_TRACE_ID.matcher(mdc).matches()) {
			return mdc;
		}
		return UUID.randomUUID().toString();
	}

	@Override
	public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
	}

	@Override
	public void close() {
	}

	@Override
	public void configure(Map<String, ?> configs) {
	}
}
