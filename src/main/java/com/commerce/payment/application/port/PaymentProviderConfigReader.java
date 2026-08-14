package com.commerce.payment.application.port;

import com.commerce.payment.application.port.dto.PaymentProviderConfig;
import com.commerce.payment.domain.PaymentPg;

/**
 * 결제사별 호출 설정을 읽는다.
 *
 * <p>어느 결제사의 설정인지를 인자로 받아, 고르는 일이 쓰는 쪽 층에서 일어나게 한다. 설정을 들고 있는
 * 쪽에 고르기까지 맡기면 그 자리가 어느 계층에도 속하지 않게 되고, 그러면 계층 규칙이 닿지 않는다.
 */
public interface PaymentProviderConfigReader {

	PaymentProviderConfig read(PaymentPg pg);
}
