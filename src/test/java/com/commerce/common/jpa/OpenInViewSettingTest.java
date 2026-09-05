package com.commerce.common.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OpenInViewSettingTest {

	@Autowired
	private Environment environment;

	@DisplayName("영속성 컨텍스트가 요청 단위로 열리지 않는다")
	@Test
	void openInView_whenContextStarts_isDisabled() {
		Boolean openInView = environment.getProperty("spring.jpa.open-in-view", Boolean.class);

		// 요청 단위로 열리면 잠금 전에 읽은 주문이 캐시로 남아 잠금 뒤 품목 읽기가 낡은 값을 본다.
		// 다른 검증은 요청 경계를 지나지 않아 이 설정이 사라져도 통과하므로 여기가 유일한 그물이다.
		assertThat(openInView).isFalse();
	}
}
