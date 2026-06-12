package com.commerce.order.presentation.batch.listener;

import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

import com.commerce.order.domain.Order;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OrderExpirationSkipListener implements SkipListener<Order, Order> {

	@Override
	public void onSkipInRead(Throwable t) {
		log.warn("Order expiration skip in read. exception={}, message={}",
			t.getClass().getSimpleName(),
			t.getMessage());
	}

	@Override
	public void onSkipInProcess(Order item, Throwable t) {
		log.warn("Order expiration skip in process. orderId={}, exception={}, message={}",
			item != null ? item.getId() : null,
			t.getClass().getSimpleName(),
			t.getMessage());
	}

	@Override
	public void onSkipInWrite(Order item, Throwable t) {
		log.warn("Order expiration skip in write. orderId={}, exception={}, message={}",
			item != null ? item.getId() : null,
			t.getClass().getSimpleName(),
			t.getMessage());
	}
}
