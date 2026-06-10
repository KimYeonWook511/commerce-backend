package com.commerce.order.application.port;

import java.util.Collection;
import java.util.Set;

public interface BlockingPaymentChecker {

	Set<Long> findOrderIdsWithBlockingPayment(Collection<Long> orderIds);
}
