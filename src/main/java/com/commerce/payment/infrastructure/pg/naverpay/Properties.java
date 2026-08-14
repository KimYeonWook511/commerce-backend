package com.commerce.payment.infrastructure.pg.naverpay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * 이 결제사를 부르는 데 필요한 설정. 패키지가 어느 결제사인지를 말하므로 이름에 넣지 않는다.
 *
 * <p>빈 이름과 설정 키를 옛 연동과 겹치지 않게 둔다. 둘이 나란히 사는 동안 같은 이름을 쓰면 하나가
 * 다른 하나를 덮어 어느 쪽이 뜨는지가 로딩 순서에 달리게 된다.
 */
@Component("pgNaverPayProperties")
@Getter
public class Properties {

	@Value("${payment.pg.naverpay.client-id}")
	private String clientId;

	@Value("${payment.pg.naverpay.client-secret}")
	private String clientSecret;

	@Value("${payment.pg.naverpay.chain-id}")
	private String chainId;

	@Value("${payment.pg.naverpay.return-url}")
	private String returnUrl;

	@Value("${payment.pg.naverpay.approval-url}")
	private String approvalUrl;

	/** 결제 번호 자리를 담은 주소. 부를 때 그 자리를 채운다 */
	@Value("${payment.pg.naverpay.history-url}")
	private String historyUrl;
}
