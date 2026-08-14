package com.commerce.payment.infrastructure.pg;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PaymentProviderConfigReader;
import com.commerce.payment.application.port.dto.PaymentProviderConfig;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.infrastructure.pg.naverpay.Properties;

import lombok.RequiredArgsConstructor;

/**
 * 결제사별 설정을 읽어 응용 계층이 쓰는 값으로 옮긴다.
 *
 * <p>결제사 목록을 그대로 가른다. 결제사가 늘면 이 자리가 컴파일 오류로 드러나므로, 설정을 채우지
 * 않은 결제사가 조용히 지나가지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PaymentProviderConfigAdapter implements PaymentProviderConfigReader {

	private final Properties naverPayProperties;

	@Override
	public PaymentProviderConfig read(PaymentPg pg) {
		return switch (pg) {
			case NAVERPAY -> new PaymentProviderConfig(
				naverPayProperties.getClientId(),
				naverPayProperties.getChainId(),
				naverPayProperties.getReturnUrl()
			);
		};
	}
}
