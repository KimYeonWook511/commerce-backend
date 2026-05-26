package com.commerce.common.log.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

import com.commerce.common.log.LogContext;

public class TraceIdRecordInterceptor implements RecordInterceptor<Object, Object> {

	@Override
	public ConsumerRecord<Object, Object> intercept(
		ConsumerRecord<Object, Object> record,
		Consumer<Object, Object> consumer
	) {
		String traceId = resolveTraceId(record);
		LogContext.putTraceId(traceId);
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
		LogContext.removeTraceId();
	}

	private String resolveTraceId(ConsumerRecord<Object, Object> record) {
		Header header = record.headers().lastHeader(LogContext.TRACE_ID_HEADER);
		if (header != null && header.value() != null) {
			String value = new String(header.value(), StandardCharsets.UTF_8);
			if (LogContext.isValidTraceId(value)) {
				return value;
			}
		}
		return UUID.randomUUID().toString();
	}
}
