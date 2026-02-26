package com.commerce.common.kafka.exception;

public class KafkaConsumeNonRetryableException extends RuntimeException {

	public KafkaConsumeNonRetryableException(String message) {
		super(message);
	}

	public KafkaConsumeNonRetryableException(String message, Throwable cause) {
		super(message, cause);
	}
}
