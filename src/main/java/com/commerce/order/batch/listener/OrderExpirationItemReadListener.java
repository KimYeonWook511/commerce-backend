package com.commerce.order.batch.listener;

import org.springframework.batch.core.ItemReadListener;
import org.springframework.stereotype.Component;

import com.commerce.order.domain.Order;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OrderExpirationItemReadListener implements ItemReadListener<Order> {

	@Override
	public void onReadError(Exception ex) {
		log.warn("Order expiration read error. exception={}, message={}",
			ex.getClass().getSimpleName(),
			ex.getMessage());
	}
}
