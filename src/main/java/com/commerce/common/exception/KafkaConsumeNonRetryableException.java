package com.commerce.common.exception;

public class KafkaConsumeNonRetryableException extends RuntimeException {

	public KafkaConsumeNonRetryableException(String message) {
		super(message);
	}

	public KafkaConsumeNonRetryableException(String message, Throwable cause) {
		super(message, cause);
	}
}
