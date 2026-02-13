package com.commerce.order.batch.listener;

import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.item.Chunk;
import org.springframework.stereotype.Component;

import com.commerce.order.domain.Order;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OrderExpirationItemWriteListener implements ItemWriteListener<Order> {

	@Override
	public void beforeWrite(Chunk<? extends Order> items) {
		log.info("Order expiration write start. count={}", items != null ? items.size() : 0);
	}

	@Override
	public void afterWrite(Chunk<? extends Order> items) {
		log.info("Order expiration write success. count={}", items != null ? items.size() : 0);
	}

	@Override
	public void onWriteError(Exception ex, Chunk<? extends Order> items) {
		log.warn("Order expiration write error. exception={}, message={}, count={}",
			ex.getClass().getSimpleName(),
			ex.getMessage(),
			items != null ? items.size() : 0);
	}
}
