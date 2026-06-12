package com.commerce.order.presentation.batch.listener;

import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OrderExpirationRetryListener implements RetryListener {

	@Override
	public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
		return true;
	}

	@Override
	public <T, E extends Throwable> void onError(
		RetryContext context,
		RetryCallback<T, E> callback,
		Throwable throwable
	) {
		log.warn("Order expiration retry error. count={}, exception={}",
			context.getRetryCount(),
			throwable.getClass().getSimpleName());
	}

	@Override
	public <T, E extends Throwable> void close(
		RetryContext context,
		RetryCallback<T, E> callback,
		Throwable throwable
	) {
	}
}
