package com.commerce.common.log.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

import com.commerce.common.log.MdcKeys;

public class TraceIdKafkaProducerInterceptor implements ProducerInterceptor<Object, Object> {

	@Override
	public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
		Headers headers = record.headers();
		if (headers.lastHeader(MdcKeys.TRACE_ID_HEADER) != null) {
			return record;
		}
		String traceId = resolveTraceId();
		headers.add(MdcKeys.TRACE_ID_HEADER, traceId.getBytes(StandardCharsets.UTF_8));
		return record;
	}

	private String resolveTraceId() {
		String mdc = MDC.get(MdcKeys.TRACE_ID);
		if (mdc != null && MdcKeys.VALID_TRACE_ID.matcher(mdc).matches()) {
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
