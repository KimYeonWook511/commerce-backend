package com.commerce.outbox.domain;

public enum OutboxEventStatus {
	PENDING,
	PUBLISHING,
	SENT,
	FAILED
}
