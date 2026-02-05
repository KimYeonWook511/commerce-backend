package com.commerce.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UlidGeneratorTest {

	@DisplayName("ULID는 26자리이며 대문자와 숫자로만 구성된다")
	@Test
	void generate_whenCalled_returnUlid() {
		String ulid = UlidGenerator.generate();

		assertThat(ulid).hasSize(26);
		assertThat(ulid).matches("^[0-9A-HJKMNPQRSTVWXYZ]{26}$");
	}
}
